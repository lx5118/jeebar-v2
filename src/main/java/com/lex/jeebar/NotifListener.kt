package com.lex.jeebar

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object InstaGate {
    private const val INSTA = "com.instagram.android"
    @Volatile var lastNotif = 0L
    @Volatile var listener: NotifListener? = null

    fun allowed(graceMs: Long): Boolean {
        if (System.currentTimeMillis() - lastNotif < graceMs) return true
        return try {
            listener?.activeNotifications?.any { it.packageName == INSTA } == true
        } catch (_: Exception) { false }
    }
}

class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() { InstaGate.listener = this }
    override fun onListenerDisconnected() { InstaGate.listener = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.instagram.android") {
            InstaGate.lastNotif = System.currentTimeMillis()
        }
    }
}
