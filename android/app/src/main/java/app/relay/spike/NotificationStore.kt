package app.relay.spike

import android.app.Notification
import app.relay.protocol.RelayMessage

/** Recent notifications, keyed by Android's notification key, so replies can find their action. */
object NotificationStore {
    private const val MAX_ENTRIES = 50

    data class Entry(
        val key: String,
        val app: String,
        val appName: String,
        val title: String,
        val text: String,
        val time: Long,
        val replyAction: Notification.Action?,
    ) {
        fun toMessage() = RelayMessage.NotificationPosted(
            id = key,
            app = app,
            appName = appName,
            title = title,
            text = text,
            time = time,
            canReply = replyAction != null,
        )
    }

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun put(entry: Entry) {
        entries.remove(entry.key)
        entries[entry.key] = entry
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.iterator().next())
    }

    @Synchronized
    fun get(key: String): Entry? = entries[key]

    @Synchronized
    fun remove(key: String): Entry? = entries.remove(key)

    /** Newest first. */
    @Synchronized
    // toList().asReversed(), not reversed(): on Android 15 SDK, reversed() binds to a
    // Java 21 method that crashes on older phones.
    fun all(): List<Entry> = entries.values.toList().asReversed()
}
