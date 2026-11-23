package com.wifistream.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.wifistream.protocol.WifiStream
import com.wifistream.sender.ShareService
import com.wifistream.sender.ShareState
import com.wifistream.sender.localWifiAddress
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the TV's QR code holds: where the TV listens, a one-time code, and (for a direct link)
 * the name and password of the small Wi-Fi Direct network the TV made.
 */
data class PairCode(val tv: String, val code: String, val ssid: String? = null, val pass: String? = null) {
    fun toUri(): String = buildString {
        append("hassasin://pair?tv=").append(enc(tv)).append("&code=").append(code)
        if (ssid != null) append("&ssid=").append(enc(ssid))
        if (pass != null) append("&pw=").append(enc(pass))
    }

    companion object {
        const val PORT = 8766

        fun parse(text: String): PairCode? {
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "hassasin" || uri.host != "pair") return null
            return PairCode(
                tv = uri.getQueryParameter("tv") ?: return null,
                code = uri.getQueryParameter("code") ?: return null,
                ssid = uri.getQueryParameter("ssid"),
                pass = uri.getQueryParameter("pw"),
            )
        }

        fun newCode(): String = (1..6).map { "ABCDEFGHJKMNPQRSTUVWXYZ23456789".random() }.joinToString("")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

/** Draws [text] as a QR code: black on white, with a quiet border, sized [px] square. */
fun qrBitmap(text: String, px: Int = 640): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(px * px) { i -> if (matrix.get(i % px, i / px)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return android.graphics.Bitmap.createBitmap(pixels, px, px, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

// ---------- the TV's side ----------

/**
 * A tiny listener on the TV while its QR code is shown. The phone that scanned it calls
 * `/pair?code=…&addr=http://phone:8765`, and the TV connects to that address.
 */
class PairListener(private val code: String, private val onPair: (String) -> Unit) {
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        Thread {
            val server = runCatching { ServerSocket(PairCode.PORT) }.getOrNull() ?: return@Thread
            socket = server
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader()
                    val line = reader.readLine().orEmpty()
                    // Read the rest of the request first; closing with unread bytes resets the connection.
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                    }
                    val query = line.substringAfter("/pair?", "").substringBefore(' ')
                    val params = query.split('&').mapNotNull {
                        val k = it.substringBefore('=', "")
                        if (k.isEmpty()) null else k to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }.toMap()
                    val ok = params["code"] == code && params["addr"] != null
                    val body = if (ok) "ok" else "wrong code"
                    client.getOutputStream().write(
                        "HTTP/1.1 ${if (ok) "200 OK" else "403 Forbidden"}\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                    )
                    client.getOutputStream().flush()
                    if (ok) onPair(params.getValue("addr"))
                }
                runCatching { client.close() }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }
}

/**
 * The TV's own small Wi-Fi network (Wi-Fi Direct), for watching without a router. The TV is the
 * group owner, always at 192.168.49.1; the phone joins with the name and password from the QR code.
 */
class DirectGroup(context: Context) {
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private val channel = manager?.initialize(context, Looper.getMainLooper(), null)

    val supported: Boolean get() = manager != null && channel != null

    /** Makes the network and returns its name and password, or an error message. */
    @SuppressLint("MissingPermission")
    suspend fun create(): Result<Pair<String, String>> {
        val m = manager ?: return Result.failure(IOException("This TV has no Wi-Fi Direct."))
        val c = channel ?: return Result.failure(IOException("This TV has no Wi-Fi Direct."))
        remove()
        val name = "DIRECT-HS-" + PairCode.newCode().take(4)
        val pass = PairCode.newCode() + PairCode.newCode()
        val started = suspendCancellableCoroutine { cont ->
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() = cont.resume(null)
                override fun onFailure(reason: Int) = cont.resume(reasonText(reason))
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val config = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pass).build()
                m.createGroup(c, config, listener)
            } else {
                m.createGroup(c, listener)
            }
        }
        if (started != null) return Result.failure(IOException(started))
        // The group takes a moment to come up; read back its real name and password.
        repeat(10) {
            delay(500)
            val group: WifiP2pGroup? = suspendCancellableCoroutine { cont -> m.requestGroupInfo(c) { cont.resume(it) } }
            if (group != null && group.isGroupOwner && !group.networkName.isNullOrEmpty() && !group.passphrase.isNullOrEmpty()) {
                return Result.success(group.networkName to group.passphrase)
            }
        }
        return Result.failure(IOException("The direct network did not start."))
    }

    fun remove() {
        val m = manager ?: return
        val c = channel ?: return
        runCatching { m.removeGroup(c, null) }
    }

    private fun reasonText(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "This TV does not support Wi-Fi Direct."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Try again in a moment."
        else -> "This TV could not start a direct network (code $reason)."
    }

    companion object {
        const val OWNER_ADDRESS = "192.168.49.1"
    }
}

/** The TV's own Wi-Fi address, for a QR code on a shared network. */
fun tvAddress(): String? = localWifiAddress()

// ---------- the phone's side ----------

/**
 * Keeps the phone on the TV's direct network for as long as sharing is on. Android drops a network
 * an app asked for as soon as nobody holds the request, so it lives here, not in a screen.
 */
object DirectLink {
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile var network: Network? = null
        private set

    suspend fun join(context: Context, ssid: String, pass: String): Network? {
        if (Build.VERSION.SDK_INT < 29) return null
        release(context)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build())
            .build()
        return withTimeoutOrNull(45_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) {
                        network = n
                        if (cont.isActive) cont.resume(n)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onLost(n: Network) {
                        if (network == n) network = null
                    }
                }
                callback = cb
                connectivity.requestNetwork(request, cb)
            }
        }
    }

    fun release(context: Context) {
        val cb = callback ?: return
        callback = null
        network = null
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }

    /** This phone's address on [n]. */
    fun addressOn(context: Context, n: Network): String? =
        context.getSystemService(ConnectivityManager::class.java).getLinkProperties(n)
            ?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
}

/**
 * Everything after the phone scans the TV's code: join the TV's direct network if it made one,
 * turn sharing on, and tell the TV where to find this phone. Returns a message for the screen.
 */
suspend fun pairWithTv(context: Context, code: PairCode, onStep: (String) -> Unit): Result<String> {
    val app = context.applicationContext
    var network: Network? = null
    var myIp: String?
    if (code.ssid != null && code.pass != null) {
        onStep("Joining the TV's direct network… confirm on screen if Android asks.")
        network = DirectLink.join(app, code.ssid, code.pass)
            ?: return Result.failure(IOException("Could not join the TV's network ${code.ssid}."))
        myIp = DirectLink.addressOn(app, network)
    } else {
        myIp = null
    }
    onStep("Turning sharing on…")
    if (!ShareState.status.value.running) ShareService.start(app)
    // Wait for the server and (on a shared network) the phone's address.
    val ready = withTimeoutOrNull(15_000) {
        while (!ShareState.status.value.running || (myIp == null && ShareState.status.value.address == null)) delay(300)
        true
    } ?: false
    if (!ready) return Result.failure(IOException(ShareState.status.value.error ?: "Sharing did not start. Choose folders to share first."))
    if (myIp == null) myIp = ShareState.status.value.address?.removePrefix("http://")?.substringBefore(':')
    val phone = "http://$myIp:${WifiStream.DEFAULT_PORT}"
    onStep("Telling the TV…")
    val tv = if (code.tv.contains(':')) code.tv else "${code.tv}:${PairCode.PORT}"
    val url = URL("http://$tv/pair?code=${code.code}&addr=${URLEncoder.encode(phone, "UTF-8")}")
    return withContext(Dispatchers.IO) {
        runCatching {
            // On the direct network the request must go out through it, not the phone's mobile data.
            val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            val status = connection.responseCode
            connection.disconnect()
            if (status != 200) throw IOException(if (status == 403) "The TV's code has changed. Scan it again." else "The TV answered $status.")
            "Connected. The TV is opening your library."
        }.recoverCatching { e ->
            throw IOException(e.message ?: "Could not reach the TV. Are both on the same Wi-Fi?")
        }
    }
}
