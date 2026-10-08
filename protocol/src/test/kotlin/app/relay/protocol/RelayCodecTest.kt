package app.relay.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RelayCodecTest {

    private val vectors: List<String> =
        File(System.getProperty("testVectors")).readLines().filter { it.isNotBlank() }

    @Test
    fun `every shared test vector parses`() {
        vectors.forEach { line -> assertNotNull(RelayCodec.decode(line), "Failed to parse: $line") }
    }

    @Test
    fun `known messages survive an encode-decode round trip`() {
        vectors.mapNotNull { RelayCodec.decode(it) }
            .filter { it !is RelayMessage.Unknown }
            .forEach { message -> assertEquals(message, RelayCodec.decode(RelayCodec.encode(message))) }
    }

    @Test
    fun `encoded messages always carry the protocol version`() {
        val line = RelayCodec.encode(RelayMessage.Ping)
        assertEquals("""{"v":0,"type":"ping"}""", line)
    }

    @Test
    fun `unknown types are kept, not rejected`() {
        val message = RelayCodec.decode("""{"v":0,"type":"future.thing","x":1}""")
        assertIs<RelayMessage.Unknown>(message)
        assertEquals("future.thing", message.type)
    }

    @Test
    fun `reply result error is optional`() {
        val ok = RelayCodec.decode("""{"v":0,"type":"reply.result","id":"k","ok":true}""")
        assertEquals(RelayMessage.ReplyResult("k", true, null), ok)
        val failed = RelayCodec.decode("""{"v":0,"type":"reply.result","id":"k","ok":false,"error":"gone"}""")
        assertEquals(RelayMessage.ReplyResult("k", false, "gone"), failed)
    }

    @Test
    fun `text with newlines and unicode is escaped onto one line`() {
        val message = RelayMessage.NotificationPosted(
            id = "k", app = "com.whatsapp", appName = "WhatsApp",
            title = "Mom", text = "Line one\nLine two — नमस्ते 👋", time = 1L, canReply = true,
        )
        val line = RelayCodec.encode(message)
        assertEquals(false, line.contains('\n'))
        assertEquals(message, RelayCodec.decode(line))
    }

    @Test
    fun `malformed input returns null instead of throwing`() {
        assertNull(RelayCodec.decode("not json"))
        assertNull(RelayCodec.decode("""{"v":0}"""))
        assertNull(RelayCodec.decode("""{"v":0,"type":"notification.reply","id":"k"}"""))
        assertNull(RelayCodec.decode("x".repeat(MAX_LINE_BYTES + 1)))
    }
}
