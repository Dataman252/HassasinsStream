package com.wifistream.receiver

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Opened by Android when a USB DAC is plugged in and the user picked this app for it. Opening it is
 * what grants the app USB access to that DAC; it shows nothing and closes at once. A player that is
 * playing notices the DAC within a second and moves the sound to it.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("UsbAttach", "USB DAC plugged in; access granted with the plug-in offer")
        // If a film is playing (or was shrunk into a window by Android's offer), bring it back full screen.
        if (PlayerSession.pipAllowed || PlayerSession.inPip) {
            startActivity(
                android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
