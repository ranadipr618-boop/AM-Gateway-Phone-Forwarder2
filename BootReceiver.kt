package com.amgateway.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or app update: restart the heartbeat and send anything still queued. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).isPaired) {
            SyncWorker.schedulePeriodic(context)
            SyncWorker.flushNow(context)
        }
    }
}
