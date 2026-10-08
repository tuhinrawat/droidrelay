package app.relay.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Experiment 0.4: a foreground service that keeps the Mac link alive and
 * records a heartbeat every minute. Gaps in the heartbeat show when the phone's
 * battery management froze or killed us.
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_LAST_BEAT, 0L)
            if (last > 0 && now - last > GAP_THRESHOLD_MS) {
                SpikeLog.log("HEARTBEAT GAP: ${(now - last) / 1000}s without running (phone froze us)")
            }
            prefs.edit().putLong(KEY_LAST_BEAT, now).apply()
            handler.postDelayed(this, BEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SpikeLog.init(this)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOULD_RUN, false)) {
            val last = prefs.getLong(KEY_LAST_BEAT, 0L)
            val ago = if (last > 0) "${(System.currentTimeMillis() - last) / 1000}s after last heartbeat" else "unknown gap"
            SpikeLog.log("SERVICE RESTARTED after being killed ($ago)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOULD_RUN, true).apply()
        MacLink.start(this)
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        SpikeLog.log("Keep-alive service running")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        SpikeLog.log("Keep-alive service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connection to Mac", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Relay is connected to your Mac")
            .setContentText("Keeps notifications flowing to your Mac")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "link"
        private const val NOTIFICATION_ID = 1
        private const val BEAT_INTERVAL_MS = 60_000L
        private const val GAP_THRESHOLD_MS = 120_000L
        private const val PREFS = "keepalive"
        private const val KEY_LAST_BEAT = "lastBeat"
        private const val KEY_SHOULD_RUN = "shouldRun"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SHOULD_RUN, false).putLong(KEY_LAST_BEAT, 0L).apply()
            MacLink.stop()
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
