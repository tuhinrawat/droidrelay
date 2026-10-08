package app.relay.protocol

import org.json.JSONObject

/** Protocol version spoken by this build. See docs/PROTOCOL.md. */
const val PROTOCOL_VERSION = 0

/** Longest line we accept, in bytes. Longer lines are dropped. */
const val MAX_LINE_BYTES = 64 * 1024

/** Every message that can travel between the phone and the Mac. */
sealed class RelayMessage {
    abstract val type: String

    data class Hello(
        val deviceId: String,
        val name: String,
        val platform: String,
        val appVersion: String,
    ) : RelayMessage() {
        override val type = "hello"
    }

    data object Ping : RelayMessage() {
        override val type = "ping"
    }

    data object Pong : RelayMessage() {
        override val type = "pong"
    }

    data class Battery(val level: Int, val charging: Boolean) : RelayMessage() {
        override val type = "battery"
    }

    data class NotificationPosted(
        val id: String,
        val app: String,
        val appName: String,
        val title: String,
        val text: String,
        val time: Long,
        val canReply: Boolean,
    ) : RelayMessage() {
        override val type = "notification.posted"
    }

    data class NotificationRemoved(val id: String) : RelayMessage() {
        override val type = "notification.removed"
    }

    data class NotificationReply(val id: String, val text: String) : RelayMessage() {
        override val type = "notification.reply"
    }

    data class ReplyResult(val id: String, val ok: Boolean, val error: String? = null) : RelayMessage() {
        override val type = "reply.result"
    }

    /** A message type this build doesn't know. Kept so newer peers don't break older ones. */
    data class Unknown(override val type: String) : RelayMessage()
}

/** Turns messages into single JSON lines and back. */
object RelayCodec {

    /** Encodes a message as one line of JSON, without the trailing newline. */
    fun encode(message: RelayMessage): String {
        val json = JSONObject()
            .put("v", PROTOCOL_VERSION)
            .put("type", message.type)
        when (message) {
            is RelayMessage.Hello -> json
                .put("deviceId", message.deviceId)
                .put("name", message.name)
                .put("platform", message.platform)
                .put("appVersion", message.appVersion)
            is RelayMessage.Battery -> json
                .put("level", message.level)
                .put("charging", message.charging)
            is RelayMessage.NotificationPosted -> json
                .put("id", message.id)
                .put("app", message.app)
                .put("appName", message.appName)
                .put("title", message.title)
                .put("text", message.text)
                .put("time", message.time)
                .put("canReply", message.canReply)
            is RelayMessage.NotificationRemoved -> json.put("id", message.id)
            is RelayMessage.NotificationReply -> json
                .put("id", message.id)
                .put("text", message.text)
            is RelayMessage.ReplyResult -> {
                json.put("id", message.id).put("ok", message.ok)
                if (message.error != null) json.put("error", message.error)
            }
            RelayMessage.Ping, RelayMessage.Pong, is RelayMessage.Unknown -> Unit
        }
        return json.toString()
    }

    /**
     * Parses one line. Returns null for anything malformed (bad JSON, missing
     * required fields, too long) so a single bad line never kills the connection.
     */
    fun decode(line: String): RelayMessage? {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return null
        val json = try {
            JSONObject(line)
        } catch (e: Exception) {
            return null
        }
        val type = json.optString("type", "")
        if (type.isEmpty()) return null
        return try {
            when (type) {
                "hello" -> RelayMessage.Hello(
                    deviceId = json.getString("deviceId"),
                    name = json.getString("name"),
                    platform = json.getString("platform"),
                    appVersion = json.optString("appVersion", ""),
                )
                "ping" -> RelayMessage.Ping
                "pong" -> RelayMessage.Pong
                "battery" -> RelayMessage.Battery(
                    level = json.getInt("level"),
                    charging = json.optBoolean("charging", false),
                )
                "notification.posted" -> RelayMessage.NotificationPosted(
                    id = json.getString("id"),
                    app = json.getString("app"),
                    appName = json.optString("appName", json.getString("app")),
                    title = json.optString("title", ""),
                    text = json.optString("text", ""),
                    time = json.optLong("time", 0L),
                    canReply = json.optBoolean("canReply", false),
                )
                "notification.removed" -> RelayMessage.NotificationRemoved(json.getString("id"))
                "notification.reply" -> RelayMessage.NotificationReply(
                    id = json.getString("id"),
                    text = json.getString("text"),
                )
                "reply.result" -> RelayMessage.ReplyResult(
                    id = json.getString("id"),
                    ok = json.getBoolean("ok"),
                    error = if (json.has("error") && !json.isNull("error")) json.getString("error") else null,
                )
                else -> RelayMessage.Unknown(type)
            }
        } catch (e: Exception) {
            null
        }
    }
}
