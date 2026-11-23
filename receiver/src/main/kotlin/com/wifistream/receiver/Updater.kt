package com.wifistream.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A newer version on GitHub. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long, val pageUrl: String)

/** Where an update stands, for the Home card and Settings › About. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val fraction: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Updates the app from its public GitHub releases: checks the newest release, downloads its APK
 * and hands it to Android's installer, which asks before installing over the current version.
 */
object Updater {
    private const val REPO = "Dataman252/HassasinsStream"
    private const val APK_NAME = "HassasinStream.apk"
    private const val PREFS = "updates"
    private const val CHECK_EVERY_MS = 24 * 3_600_000L
    private const val ACTION_RESULT = "com.wifistream.receiver.UPDATE_RESULT"

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** The Home card can be closed until the next version comes out. */
    var dismissedVersion by mutableStateOf<String?>(null)
        private set

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    fun dismiss(context: Context, version: String) {
        dismissedVersion = version
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dismissed", version).apply()
    }

    /** Checks once a day by itself; [force] checks now (Settings › About). */
    suspend fun check(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dismissedVersion = prefs.getString("dismissed", null)
        if (state is UpdateState.Downloading || state is UpdateState.Installing) return
        val last = prefs.getLong("checked_at", 0)
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            // Between checks, still show what the last check found.
            prefs.getString("found", null)?.let { saved ->
                runCatching { decodeSaved(saved) }.getOrNull()?.takeIf { newer(it.version, installedVersion(context)) }?.let {
                    state = UpdateState.Available(it)
                }
            }
            return
        }
        state = UpdateState.Checking
        state = try {
            val info = latest()
            prefs.edit().putLong("checked_at", System.currentTimeMillis()).putString("found", info?.let(::encodeSaved)).apply()
            if (info != null && newer(info.version, installedVersion(context))) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            UpdateState.Failed("Could not check for updates: ${e.message ?: "no internet"}")
        }
    }

    /** Downloads the new APK, then asks Android to install it. */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // A one-time switch: allow this app to install updates.
            openInstallPermission(context)
            state = UpdateState.Failed("Allow \"Install unknown apps\" for Hassasin's Stream, then press Update again.", info)
            return
        }
        val file = File(context.cacheDir, "updates/HassasinStream-${info.version}.apk")
        try {
            state = UpdateState.Downloading(info, 0f)
            download(info, file)
            state = UpdateState.Installing(info)
            install(context, file)
        } catch (e: Exception) {
            file.delete()
            state = UpdateState.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}", info)
        }
    }

    private suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HassasinsStream-updater")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val o = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val tag = (o["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@withContext null
            val asset = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == APK_NAME }
                ?: (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true }
                ?: return@withContext null
            UpdateInfo(
                version = tag.removePrefix("v"),
                notes = (o["body"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                apkUrl = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: return@withContext null,
                sizeBytes = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0,
                pageUrl = (o["html_url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
    }

    private suspend fun download(info: UpdateInfo, file: File) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.parentFile?.listFiles()?.filter { it != file }?.forEach { it.delete() }
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "HassasinsStream-updater").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("download answered ${response.code}")
            val body = response.body ?: throw IOException("empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            state = UpdateState.Downloading(info, percent / 100f)
                        }
                    }
                }
            }
            if (info.sizeBytes > 0 && file.length() != info.sizeBytes) throw IOException("the download was incomplete")
        }
    }

    /** Hands the APK to Android's installer; Android shows its own "Update this app?" question. */
    private suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            registerResult(app)
            val intent = Intent(ACTION_RESULT).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, sessionId, intent, flags).intentSender)
        }
    }

    private var receiver: BroadcastReceiver? = null

    private fun registerResult(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                    }
                    PackageInstaller.STATUS_SUCCESS -> state = UpdateState.UpToDate
                    else -> {
                        val info = (state as? UpdateState.Installing)?.info
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "it was cancelled"
                        state = UpdateState.Failed("Not installed: $message", info)
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, filter)
        receiver = r
    }

    private fun openInstallPermission(context: Context) {
        val uri = Uri.parse("package:${context.packageName}")
        val opened = runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** True when [candidate] ("1.10") is a later version than [current] ("1.9"). */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun encodeSaved(i: UpdateInfo) = listOf(i.version, i.apkUrl, i.sizeBytes.toString(), i.pageUrl, i.notes).joinToString("\u0001")
    private fun decodeSaved(s: String): UpdateInfo {
        val p = s.split("\u0001")
        return UpdateInfo(p[0], p.getOrElse(4) { "" }, p[1], p[2].toLong(), p[3])
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
