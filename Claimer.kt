package com.amgateway.smsforwarder

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Swaps the one-time connect token from the dashboard for the signing secret. Runs once, at connect time. */
object Claimer {
    class Result(val ok: Boolean, val msg: String)

    fun claim(ctx: Context, url: String, token: String): Result {
        if (!url.startsWith("https://")) return Result(false, "The gateway address must start with https")
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "2" } catch (e: Exception) { "2" }
        val body = JSONObject().put("t", token)
            .put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim().take(60))
            .put("app", version).toString()
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (code in 200..299 && j.optBoolean("ok")) {
                val hook = j.optString("url")
                val conn = j.optLong("conn", 0L)
                val secret = j.optString("secret")
                // the signing endpoint must live on the same host the code came from
                if (!hook.startsWith("https://") || conn <= 0L || secret.length < 32 || URL(hook).host != URL(url).host)
                    return Result(false, "The gateway answered with unexpected data")
                val p = Prefs(ctx)
                p.url = hook; p.conn = conn; p.secret = secret
                p.applyConfig(j.optJSONObject("config"))
                p.lastOk = System.currentTimeMillis(); p.lastError = ""
                return Result(true, "Connected")
            }
            return Result(false, when {
                code == 404 -> "This code is invalid or has expired. Create a new connect link on the dashboard."
                code == 429 -> "Too many attempts. Wait a few minutes and try again."
                else -> "The gateway answered HTTP $code"
            })
        } catch (e: IOException) {
            return Result(false, "No connection: " + (e.message ?: "network error"))
        } catch (e: Exception) {
            return Result(false, "Error: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            c?.disconnect()
        }
    }
}
