package app.relay.spike

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.relay.protocol.RelayMessage

/**
 * Experiment 0.2: reads every notification and can reply to it through the
 * app's own reply action (the same mechanism Wear OS watches use).
 */
class RelayNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        SpikeLog.init(this)
        SpikeLog.log("Notification access: listener connected")
    }

    override fun onListenerDisconnected() {
        SpikeLog.log("Notification access: listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val entry = NotificationStore.Entry(
            key = sbn.key,
            app = sbn.packageName,
            appName = appLabel(sbn.packageName),
            title = title,
            text = text,
            time = sbn.postTime,
            replyAction = findReplyAction(notification),
        )
        NotificationStore.put(entry)
        SpikeLog.log("Posted: ${entry.appName} · ${entry.title} (reply=${entry.replyAction != null})")
        MacLink.send(entry.toMessage())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (NotificationStore.remove(sbn.key) != null) {
            MacLink.send(RelayMessage.NotificationRemoved(sbn.key))
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        val candidates = (notification.actions?.toList() ?: emptyList()) +
            Notification.WearableExtender(notification).actions
        return candidates.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
    }

    companion object {
        /** Sends [text] as a reply to the notification with [key]. Safe to call from any thread. */
        fun reply(context: Context, key: String, text: String): RelayMessage.ReplyResult {
            val entry = NotificationStore.get(key)
                ?: return RelayMessage.ReplyResult(key, false, "Notification no longer exists")
            val action = entry.replyAction
                ?: return RelayMessage.ReplyResult(key, false, "This notification has no reply action")
            val inputs = action.remoteInputs ?: emptyArray()
            return try {
                val results = Bundle()
                inputs.forEach { results.putCharSequence(it.resultKey, text) }
                val intent = Intent()
                RemoteInput.addResultsToIntent(inputs, intent, results)
                action.actionIntent.send(context, 0, intent)
                SpikeLog.log("Reply sent to ${entry.appName}: \"$text\"")
                RelayMessage.ReplyResult(key, true)
            } catch (e: PendingIntent.CanceledException) {
                SpikeLog.log("Reply FAILED (${entry.appName}): action cancelled")
                RelayMessage.ReplyResult(key, false, "Reply action was cancelled by the app")
            } catch (e: Exception) {
                SpikeLog.log("Reply FAILED (${entry.appName}): ${e.javaClass.simpleName}: ${e.message}")
                RelayMessage.ReplyResult(key, false, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
