package com.example.devicesync

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotificationRelay : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val app = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        val payload = JSONObject().apply {
            put("type", "notification")
            put("app", app)
            put("title", title)
            put("ts", System.currentTimeMillis())
        }.toString()
        SyncNodeService.instance?.broadcastRaw(payload)
    }
}
