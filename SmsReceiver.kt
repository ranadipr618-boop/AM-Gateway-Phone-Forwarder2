package com.amgateway.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Receives every incoming SMS, keeps only payment credits from the sender IDs the merchant listed, queues them and sends them at once. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val p = Prefs(context)
        if (!p.isPaired) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs == null || msgs.isEmpty()) return
        val sender = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        if (body.isBlank() || !Protocol.senderMatches(p.senders, sender) || !Protocol.looksLikeCredit(body)) return

        val recv = msgs[0].timestampMillis.let { if (it > 0L) it else System.currentTimeMillis() }
        if (QueueDb.get(context).add(sender, body, recv)) p.log("Payment SMS from $sender queued")
        SyncWorker.flushNow(context)                      // safety net: retries with back-off if the send below fails

        val pending = goAsync()
        Thread {
            try { Uploader.flush(context) } catch (e: Exception) { p.log("Send error: " + (e.message ?: "")) } finally { pending.finish() }
        }.start()
    }
}
