package com.amgateway.smsforwarder

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pure logic shared with the gateway (sms-hook.php). No Android classes here so it can be unit-tested on a plain JVM. */
object Protocol {
    const val PAIR_PREFIX = "AMGSMS1:"

    /** lowercase hex HMAC-SHA256(secret, "<ts>\n<body>") — must equal hash_hmac('sha256', $ts."\n".$raw, $secret) in PHP */
    fun sign(secret: String, tsSeconds: Long, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal("$tsSeconds\n$body".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    private fun alnum(s: String): String = s.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }

    /** SMS sender ("VM-BHARTP", "AX-HDFCBK-S") against the comma separated list the merchant typed: substring match, letters and digits only */
    fun senderMatches(list: String, sender: String): Boolean {
        val s = alnum(sender)
        return list.split(',', ' ', ';', '\n').map { alnum(it) }.filter { it.length >= 3 }.any { s.contains(it) }
    }

    private val creditWords = Regex("(credited|received|deposited|credit of|paid you|UPI/CR/)", RegexOption.IGNORE_CASE)
    private val blockWords = Regex("(\\botp\\b|one time password|debited|\\bsent\\b)", RegexOption.IGNORE_CASE)

    /** notification source: the package name must contain one of the lowercase words the gateway sent (e.g. "fampay", "com.phonepe.app") */
    fun pkgMatches(list: String, pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return list.split(',', ' ', ';', '\n').map { it.trim().lowercase(Locale.ROOT) }.filter { it.length >= 3 }.any { p.contains(it) }
    }

    /** privacy filter: only SMS that look like an incoming payment ever leave the phone (never OTPs, chats or other mail) */
    fun looksLikeCredit(body: String): Boolean = creditWords.containsMatchIn(body) && !blockWords.containsMatchIn(body)
}
