package com.amgateway.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("amg_sms", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) { sp.edit().putString("url", v).apply() }
    var conn: Long
        get() = sp.getLong("conn", 0L)
        set(v) { sp.edit().putLong("conn", v).apply() }
    var secret: String
        get() = sp.getString("secret", "") ?: ""
        set(v) { sp.edit().putString("secret", v).apply() }
    var senders: String
        get() = sp.getString("senders", "") ?: ""
        set(v) { sp.edit().putString("senders", v).apply() }
    /** notification sources (package-name words) and display name, both pushed by the gateway */
    var pkgs: String
        get() = sp.getString("pkgs", "") ?: ""
        set(v) { sp.edit().putString("pkgs", v).apply() }
    var title: String
        get() = sp.getString("title", "") ?: ""
        set(v) { sp.edit().putString("title", v).apply() }
    var lastOk: Long
        get() = sp.getLong("last_ok", 0L)
        set(v) { sp.edit().putLong("last_ok", v).apply() }
    var lastError: String
        get() = sp.getString("last_error", "") ?: ""
        set(v) { sp.edit().putString("last_error", v).apply() }

    val isPaired: Boolean get() = url.startsWith("https://") && conn > 0 && secret.length >= 32

    @Synchronized
    fun log(msg: String) {
        val stamp = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
        val old = (sp.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }
        sp.edit().putString("log", (old + "$stamp  $msg").takeLast(40).joinToString("\n")).apply()
    }

    fun logText(): String = (sp.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }.reversed().joinToString("\n")

    fun clearPairing() { sp.edit().remove("url").remove("conn").remove("secret").remove("senders").remove("pkgs").remove("title").apply() }

    /** the gateway is the only place anything is configured: senders, notification sources and the provider name arrive here */
    fun applyConfig(j: JSONObject?) {
        if (j == null) return
        senders = j.optString("senders", senders)
        pkgs = j.optString("pkgs", pkgs)
        title = j.optString("title", title)
    }
}

/** The pairing code made by the gateway: "AMGSMS1:" + base64url(JSON {v,u,c,k}) */
object Pairing {
    class Data(val url: String, val conn: Long, val secret: String)

    fun parse(raw: String): Data? {
        val t = raw.filter { !it.isWhitespace() }
        if (!t.startsWith(Protocol.PAIR_PREFIX)) return null
        return try {
            val bytes = Base64.decode(t.removePrefix(Protocol.PAIR_PREFIX), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            val u = j.getString("u")
            val c = j.getLong("c")
            val k = j.getString("k")
            if (!u.startsWith("https://") || c <= 0L || k.length < 32) null else Data(u, c, k)
        } catch (e: Exception) {
            null
        }
    }
}
