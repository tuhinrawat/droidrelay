package app.relay.spike

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timestamped event log for the Phase 0 experiments. Written to a file so
 * results survive the app being killed, which is exactly what 0.4 measures.
 */
object SpikeLog {
    private const val MAX_MEMORY_LINES = 200
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "spike-log.txt")
    }

    fun log(message: String) {
        val line = "${format.format(Date())}  $message"
        val toNotify: List<() -> Unit>
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX_MEMORY_LINES) lines.removeFirst()
            runCatching { file?.appendText(line + "\n") }
            toNotify = listeners.toList()
        }
        toNotify.forEach { it() }
    }

    @Synchronized
    fun recent(count: Int): List<String> = lines.toList().takeLast(count)

    @Synchronized
    fun logFile(): File? = file

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
