package com.amgateway.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.net.URL

/**
 * One screen. The merchant taps "Open app & connect" on the dashboard (deep link amgsms://connect?u=..&t=..), confirms the gateway address,
 * and the app connects itself, then walks through the four permissions one after the other. Nothing is typed or pasted.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 5000) }
    }
    private val asked = HashSet<Int>()
    private var walking = false
    private var claiming = false

    private fun tv(id: Int): TextView = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tv(R.id.btnConnect).setOnClickListener { connectTyped() }
        tv(R.id.btnContinue).setOnClickListener { asked.clear(); walking = true; nextStep() }
        tv(R.id.btnTest).setOnClickListener { sendTest() }
        tv(R.id.btnApp).setOnClickListener { openAppSettings() }
        tv(R.id.btnDisconnect).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Disconnect this phone?")
                .setMessage("Payments will stop being confirmed automatically until you connect again.")
                .setPositiveButton("Disconnect") { _, _ -> prefs.clearPairing(); prefs.log("Disconnected"); refresh() }
                .setNegativeButton("Cancel", null).show()
        }
        for (n in 1..4) {
            val row = findViewById<View>(rowId(n))
            row.setOnClickListener { launchStep(n) }
        }
        if (prefs.isPaired) SyncWorker.schedulePeriodic(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        if (walking) ui.postDelayed({ nextStep() }, 350)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ---------------------------------------------------------------- connect

    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "amgsms" || d.host != "connect") return
        i.data = null                                   // handle a link once, not again after rotation
        val u = d.getQueryParameter("u") ?: ""
        val t = d.getQueryParameter("t") ?: ""
        confirmConnect(u, t)
    }

    private fun connectTyped() {
        val gw = (findViewById<EditText>(R.id.gw)).text.toString().trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val code = (findViewById<EditText>(R.id.code)).text.toString()
        if (gw.isEmpty() || gw.contains(' ')) { toast("Type the gateway address shown on the dashboard"); return }
        confirmConnect("https://$gw/device-pair.php", code)
    }

    private fun confirmConnect(url: String, rawToken: String) {
        val token = rawToken.filter { it.isLetterOrDigit() }.uppercase()
        val host = try { URL(url).host } catch (e: Exception) { "" }
        if (!url.startsWith("https://") || host.isEmpty() || token.length != 8) { toast("This connect link is not valid. Create a new one on the dashboard."); return }
        AlertDialog.Builder(this)
            .setTitle("Connect to this gateway?")
            .setMessage("This phone will send your payment alerts to:\n\n$host\n\nContinue only if you opened this from your own gateway dashboard.")
            .setPositiveButton("Connect") { _, _ -> doClaim(url, token) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doClaim(url: String, token: String) {
        if (claiming) return
        claiming = true
        toast("Connecting...")
        Thread {
            val r = Claimer.claim(applicationContext, url, token)
            runOnUiThread {
                claiming = false
                if (r.ok) {
                    prefs.log("Connected to " + prefs.url.removePrefix("https://").substringBefore('/'))
                    SyncWorker.schedulePeriodic(this)
                    SyncWorker.flushNow(this)
                    asked.clear(); walking = true
                    refresh()
                    nextStep()
                } else {
                    prefs.log("Connect failed: " + r.msg)
                    toast(r.msg); refresh()
                }
            }
        }.start()
    }

    private fun sendTest() {
        if (!prefs.isPaired) { toast("Not connected yet"); return }
        toast("Contacting gateway...")
        Thread {
            val r = Uploader.ping(applicationContext)
            prefs.log(if (r.ok) "Test OK - gateway reachable" else "Test failed: " + r.msg)
            runOnUiThread { toast(if (r.ok) "Connected to the gateway" else r.msg); refresh() }
        }.start()
    }

    // ---------------------------------------------------------------- permissions

    private fun rowId(n: Int) = when (n) { 1 -> R.id.row1; 2 -> R.id.row2; 3 -> R.id.row3; else -> R.id.row4 }
    private fun dotId(n: Int) = when (n) { 1 -> R.id.dot1; 2 -> R.id.dot2; 3 -> R.id.dot3; else -> R.id.dot4 }
    private fun actId(n: Int) = when (n) { 1 -> R.id.act1; 2 -> R.id.act2; 3 -> R.id.act3; else -> R.id.act4 }

    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun hasListener() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    private fun hasSms() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    private fun hasBattery(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm != null && pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun granted(n: Int) = when (n) { 1 -> hasNotif(); 2 -> hasListener(); 3 -> hasSms(); else -> hasBattery() }
    private fun missing(): List<Int> = (1..4).filter { !granted(it) }

    /** opens the next permission that is still missing and has not been offered yet in this round */
    private fun nextStep() {
        val m = missing().firstOrNull { it !in asked }
        if (m == null) {
            walking = false
            if (missing().isEmpty()) toast("All set. Payments are now confirmed automatically.")
            refresh()
            return
        }
        asked.add(m)
        launchStep(m)
    }

    private fun launchStep(n: Int) {
        try {
            when (n) {
                1 -> if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 21)
                2 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                3 -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), 22)
                else -> openBatterySettings()
            }
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        try {
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            else startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    // ---------------------------------------------------------------- screen

    private fun refresh() {
        val paired = prefs.isPaired
        val miss = missing()
        val host = prefs.url.removePrefix("https://").substringBefore('/')
        val name = if (prefs.title.isNotEmpty()) prefs.title else "Gateway"

        findViewById<View>(R.id.connectCard).visibility = if (paired) View.GONE else View.VISIBLE
        findViewById<View>(R.id.permCard).visibility = if (paired) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnContinue).visibility = if (paired && miss.isNotEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnTest).visibility = if (paired) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnDisconnect).visibility = if (paired) View.VISIBLE else View.GONE

        val dot = findViewById<View>(R.id.stDot)
        val recent = prefs.lastOk > 0L && System.currentTimeMillis() - prefs.lastOk < 45L * 60L * 1000L
        when {
            !paired -> { dot.setBackgroundResource(R.drawable.dot_bad); tv(R.id.stTitle).text = "Not connected"; tv(R.id.stSub).text = "Open your gateway dashboard on this phone" }
            miss.isNotEmpty() -> { dot.setBackgroundResource(R.drawable.dot_warn); tv(R.id.stTitle).text = "Almost done"; tv(R.id.stSub).text = "$name · allow the items below" }
            recent && prefs.lastError.isEmpty() -> { dot.setBackgroundResource(R.drawable.dot_ok); tv(R.id.stTitle).text = "Connected"; tv(R.id.stSub).text = "$name · $host" }
            else -> { dot.setBackgroundResource(R.drawable.dot_warn); tv(R.id.stTitle).text = "Connected, checking gateway"; tv(R.id.stSub).text = if (prefs.lastError.isNotEmpty()) prefs.lastError else "$name · $host" }
        }

        for (n in 1..4) {
            val ok = granted(n)
            findViewById<View>(dotId(n)).setBackgroundResource(if (ok) R.drawable.dot_ok else R.drawable.dot_bad)
            tv(actId(n)).text = if (ok) "✓" else "Allow"
            tv(actId(n)).setTextColor(ContextCompat.getColor(this, if (ok) R.color.amg_ok else R.color.amg_white))
        }
        findViewById<View>(rowId(1)).visibility = if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hint).visibility = if (Build.VERSION.SDK_INT >= 33 && (!hasListener() || !hasSms())) View.VISIBLE else View.GONE
        tv(R.id.log).text = prefs.logText().ifEmpty { "(nothing yet)" }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
