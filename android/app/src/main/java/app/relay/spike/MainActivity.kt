package app.relay.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * One test screen for all Phase 0 Android experiments. Plain platform widgets,
 * no libraries — this is throwaway spike code.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var latest: TextView
    private lateinit var replyInput: EditText
    private lateinit var logView: TextView
    private val onLog: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun heading(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }.also { column.addView(it) }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }.also { column.addView(it) }

        heading("Relay spike — Phase 0")
        status = TextView(this).also { column.addView(it) }

        heading("0.2 · Notifications + reply")
        button("1. Open notification access settings") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        latest = TextView(this).also { column.addView(it) }
        replyInput = EditText(this).apply { hint = "Reply text for the latest replyable notification" }
            .also { column.addView(it) }
        button("2. Reply to latest replyable notification (from phone)") { replyLocally() }

        heading("0.4 · Stay alive in background + 0.5 Mac link")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            button("Allow this app's status notification") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        button("Start keep-alive + connect to Mac") { KeepAliveService.start(this) }
        button("Stop keep-alive") { KeepAliveService.stop(this) }
        button("Associate Mac as companion device (Android 13+)") { CompanionHelper.associate(this) }
        button("Open battery optimisation settings") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        button("Open this app's settings (battery: set Unrestricted)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }

        heading("Log")
        button("Share full log file") { shareLog() }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }.also { column.addView(it) }

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        SpikeLog.addListener(onLog)
        refresh()
    }

    override fun onPause() {
        SpikeLog.removeListener(onLog)
        super.onPause()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CompanionHelper.REQUEST_ASSOCIATE && resultCode != RESULT_OK) {
            SpikeLog.log("CDM: user cancelled the device picker")
        }
        refresh()
    }

    private fun refresh() {
        val access = hasNotificationAccess()
        val postAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        status.text = buildString {
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Notification access: ${if (access) "GRANTED" else "not granted"}")
            appendLine("Status notification allowed: $postAllowed")
            appendLine("Mac link: ${MacLink.state}${MacLink.macName?.let { " ($it)" } ?: ""}")
            appendLine("Battery-optimisation exempt: ${CompanionHelper.isBatteryExempt(this@MainActivity)}")
            append("Companion associations: ${CompanionHelper.associationCount(this@MainActivity)}")
        }
        val entries = NotificationStore.all()
        latest.text = if (entries.isEmpty()) {
            "No notifications captured yet. Send yourself a WhatsApp/SMS."
        } else {
            entries.take(5).joinToString("\n") { "• ${it.appName}: ${it.title} — ${it.text.take(40)}${if (it.replyAction != null) "  [can reply]" else ""}" }
        }
        logView.text = SpikeLog.recent(60).asReversed().joinToString("\n")
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun replyLocally() {
        val target = NotificationStore.all().firstOrNull { it.replyAction != null }
        if (target == null) {
            SpikeLog.log("No replyable notification captured yet")
            return
        }
        val text = replyInput.text.toString().ifBlank { "Test reply from Relay spike" }
        thread { RelayNotificationListener.reply(applicationContext, target.key, text) }
        replyInput.setText("")
    }

    private fun shareLog() {
        val file = SpikeLog.logFile() ?: return
        val body = runCatching { file.readText() }.getOrDefault("(log empty)")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Relay spike log · ${Build.MANUFACTURER} ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, body.takeLast(90_000))
        }
        startActivity(Intent.createChooser(send, "Share spike log"))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
