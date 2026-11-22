package com.amgateway.smsforwarder

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Signed HTTPS calls to the gateway's sms-hook.php. */
object Uploader {
    /** ok = gateway accepted · retry = try again later (network / busy / clock) · drop = gateway will never accept this SMS (remove it) */
    class Res(val ok: Boolean, val retry: Boolean, val msg: String, val drop: Boolean = false, val json: JSONObject? = null)

    enum class Flush { DONE, RETRY, BLOCKED }

    private fun device(): String = (Build.MANUFACTURER + " " + Build.MODEL).trim().take(60)

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1"
    } catch (e: Exception) {
        "1"
    }

    private fun call(p: Prefs, payload: JSONObject): Res {
        val body = payload.toString()
        val ts = System.currentTimeMillis() / 1000L
        val sig = Protocol.sign(p.secret, ts, body)
        var c: HttpURLConnection? = null
        try {
            c = URL(p.url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("X-AMG-Conn", p.conn.toString())
            c.setRequestProperty("X-AMG-Ts", ts.toString())
            c.setRequestProperty("X-AMG-Sig", sig)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            val err = j.optString("error")
            return when {
                code in 200..299 && j.optBoolean("ok") -> Res(true, false, j.optString("result", "ok"), false, j)
                code == 401 && err == "clock_skew" -> Res(false, true, "Phone date/time is wrong - turn on automatic date & time")
                code == 401 -> Res(false, false, "Connection rejected - open the dashboard and create a new connect link")
                code == 404 -> Res(false, false, "Connection not found on the gateway - pair again")
                code == 403 -> Res(false, false, "Merchant account is not active")
                code == 400 -> Res(false, false, "Gateway rejected the request ($err)", drop = true)
                code == 429 || code == 408 || code >= 500 -> Res(false, true, "Gateway busy or error (HTTP $code)")
                else -> Res(false, true, "Unexpected answer (HTTP $code)")
            }
        } catch (e: IOException) {
            return Res(false, true, "No connection: " + (e.message ?: "network error"))
        } catch (e: Exception) {
            return Res(false, true, "Error: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            c?.disconnect()
        }
    }

    /** heartbeat + connectivity test */
    fun ping(ctx: Context): Res {
        val p = Prefs(ctx)
        if (!p.isPaired) return Res(false, false, "Not paired yet")
        val j = JSONObject().put("type", "ping").put("device", device()).put("app", appVersion(ctx))
        val r = call(p, j)
        if (r.ok) { p.lastOk = System.currentTimeMillis(); p.lastError = ""; p.applyConfig(r.json?.optJSONObject("config")) } else p.lastError = r.msg
        return r
    }

    /** sends every queued SMS, oldest first. Stops at the first problem so the order is kept. */
    @Synchronized
    fun flush(ctx: Context): Flush {
        val p = Prefs(ctx)
        if (!p.isPaired) return Flush.BLOCKED
        val db = QueueDb.get(ctx)
        while (true) {
            val items = db.next(20)
            if (items.isEmpty()) return Flush.DONE
            for (item in items) {
                val j = JSONObject().put("type", "sms").put("sender", item.sender).put("body", item.body)
                    .put("received", item.recv).put("device", device()).put("app", appVersion(ctx))
                val r = call(p, j)
                if (r.ok) {
                    db.remove(item.id)
                    p.lastOk = System.currentTimeMillis(); p.lastError = ""
                    p.log("Gateway: " + r.msg.replace('_', ' ') + " (" + item.sender + ")")
                } else if (r.drop) {
                    db.remove(item.id)
                    p.log("Dropped SMS: " + r.msg)
                } else {
                    p.lastError = r.msg
                    p.log(r.msg)
                    return if (r.retry) Flush.RETRY else Flush.BLOCKED
                }
            }
        }
    }
}
