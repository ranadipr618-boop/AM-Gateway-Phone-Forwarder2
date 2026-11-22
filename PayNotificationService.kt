package com.amgateway.smsforwarder

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads payment notifications (FamPay, BharatPe, GPay, PhonePe, Paytm, BHIM ...). Privacy: a notification is looked at only when its package
 * is on the list the gateway sent AND its text looks like incoming money; everything else is ignored on the spot and never stored or sent.
 */
class PayNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val p = Prefs(this)
            if (!p.isPaired || p.pkgs.isBlank()) return
            val pkg = sbn.packageName ?: return
            if (pkg == packageName || !Protocol.pkgMatches(p.pkgs, pkg)) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val body = (title + "\n" + text).trim()
            if (body.isBlank() || !Protocol.looksLikeCredit(body)) return
            val recv = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()
            if (QueueDb.get(this).add("APP:$pkg", body, recv)) p.log("Payment alert from $pkg queued")
            SyncWorker.flushNow(this)                        // safety net with back-off
            val ctx = applicationContext
            Thread {
                try { Uploader.flush(ctx) } catch (e: Exception) { p.log("Send error: " + (e.message ?: "")) }
            }.start()
        } catch (e: Exception) {
            // never let a bad notification crash the listener
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try { NotificationListenerService.requestRebind(ComponentName(this, PayNotificationService::class.java)) } catch (e: Exception) { }
    }
}
