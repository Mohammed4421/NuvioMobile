package com.nuvio.android

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only (Nuvio Plus, Build 15 download contract condition 9). Emulator notification
 * shades did not reliably show the download notification to UI automation, so the test sends the
 * action the way the notification button does: it takes the posted download notification from the
 * system (NotificationManager.activeNotifications), finds the action whose label is
 * `--es action <label>` and sends that action's own PendingIntent. The app stays in the
 * background; nothing else of the app is called. Result in logcat (tag NuvioNotificationTest).
 * The screen tap itself (system UI) is checked on a device.
 */
class NotificationActionTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("action").orEmpty()
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = manager.activeNotifications.map { it.notification }
        val action = notifications.firstNotNullOfOrNull { notification ->
            notification.actions?.firstOrNull { it.title?.toString().equals(label, ignoreCase = true) }
        }
        if (action == null) {
            Log.w(TAG, "RESULT no-action label=$label notifications=${notifications.size} " +
                "actions=${notifications.flatMap { n -> n.actions.orEmpty().map { it.title } }}")
            resultCode = 2
            return
        }
        action.actionIntent.send()
        Log.i(TAG, "RESULT sent label=$label")
        resultCode = 1
    }

    private companion object {
        const val TAG = "NuvioNotificationTest"
    }
}
