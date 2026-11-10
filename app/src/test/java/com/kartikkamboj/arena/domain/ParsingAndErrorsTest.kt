package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.data.model.AppError
import com.kartikkamboj.arena.data.model.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** Markdown parsing for locally stored notes and imported excerpts. */
class MarkdownParserTest {

    private fun List<MdInline>.text(): String = joinToString("") { it.text }

    @Test
    fun `headings paragraphs and rules`() {
        val doc = MarkdownParser.parse("## Title\n\nSome body text\n---\n")
        assertEquals(3, doc.blocks.size)
        val heading = doc.blocks[0] as MdBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("Title", heading.inline.text())
        assertEquals(MdBlock.Rule, doc.blocks[2])
    }

    @Test
    fun `fenced code keeps lines and language`() {
        val doc = MarkdownParser.parse("```kotlin\nfun main() {\n  println(1)\n}\n```")
        val code = doc.blocks.single() as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals(listOf("fun main() {", "  println(1)", "}"), code.lines)
    }

    @Test
    fun `an unclosed fence still yields its content rather than dropping the answer`() {
        val doc = MarkdownParser.parse("```python\nprint('hi')")
        val code = doc.blocks.single { it is MdBlock.Code } as MdBlock.Code
        assertEquals(listOf("print('hi')"), code.lines)
    }

    @Test
    fun `lists keep order and nesting is flattened not guessed`() {
        val doc = MarkdownParser.parse("- one\n- two\n- three\n")
        val list = doc.blocks.single() as MdBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(3, list.items.size)
        assertEquals("one", list.items[0].text())
    }

    @Test
    fun `quotes keep their inline content without the marker`() {
        val doc = MarkdownParser.parse("> careful **now**")
        val quote = doc.blocks.single() as MdBlock.Quote
        assertEquals("careful now", quote.inline.text())
    }

    @Test
    fun `empty input has no blocks`() {
        assertTrue(MarkdownParser.parse("").blocks.isEmpty())
        assertTrue(MarkdownParser.parse("   \n  \n").blocks.isEmpty())
    }

    @Test
    fun `plain text keeps the markup readable for a copy action`() {
        val doc = MarkdownParser.parse("# Hi\n\n```js\nlet a = 1\n```")
        val plain = doc.toPlainText()
        assertTrue(plain.contains("Hi"))
        assertTrue(plain.contains("let a = 1"))
        assertFalse("fences are not part of the text a user pastes elsewhere", plain.contains("```"))
    }

    @Test
    fun `inline styles survive round trips`() {
        val inlines = MarkdownParser.inline("This is **bold**, this is *italic*, this is `code`.")
        assertTrue(inlines.any { it.styles.contains(MdStyle.BOLD) && it.text == "bold" })
        assertTrue(inlines.any { it.styles.contains(MdStyle.ITALIC) && it.text == "italic" })
        assertTrue(inlines.any { it.styles.contains(MdStyle.CODE) && it.text == "code" })
    }

    @Test
    fun `unmatched markers stay as literal text`() {
        val inlines = MarkdownParser.inline("2 * 3 * 4 and _unfinished")
        assertEquals("2 * 3 * 4 and _unfinished", inlines.text())
    }

    @Test
    fun `links are kept as text with the target shown`() {
        val inlines = MarkdownParser.inline("see [the docs](https://arena.ai/leaderboard) now")
        val link = inlines.first { it.styles.contains(MdStyle.LINK) }
        assertEquals("the docs", link.text)
        assertEquals("https://arena.ai/leaderboard", link.link)
        assertTrue(inlines.text().contains("the docs"))
    }

    @Test
    fun `javascript links are dropped and only the label survives`() {
        val inlines = MarkdownParser.inline("[click](javascript:alert(1))")
        val link = inlines.first { it.styles.contains(MdStyle.LINK) }
        assertTrue("the target must not be carried", link.link.isNullOrEmpty())
        assertEquals("click", link.text)
    }

    @Test
    fun `very long input is clipped rather than parsed forever`() {
        val huge = ("word ".repeat(2_000) + "\n\n").repeat(60)
        val doc = MarkdownParser.parse(huge)
        assertTrue(doc.blocks.size <= MarkdownParser.MAX_LINES)
    }
}

class ErrorMapperTest {

    @Test
    fun `http statuses map onto distinct user-facing cases`() {
        assertEquals(ErrorKind.UNAUTHORIZED, ErrorMapper.fromHttpStatus(401))
        assertEquals(ErrorKind.FORBIDDEN, ErrorMapper.fromHttpStatus(403))
        assertEquals(ErrorKind.RATE_LIMITED, ErrorMapper.fromHttpStatus(429))
        assertEquals(ErrorKind.PARSE, ErrorMapper.fromHttpStatus(404))
        assertEquals(ErrorKind.SERVER, ErrorMapper.fromHttpStatus(500))
        assertEquals(ErrorKind.SERVER, ErrorMapper.fromHttpStatus(503))
    }

    @Test
    fun `only 401 and 403 are treated as auth failures`() {
        assertTrue(ErrorMapper.isAuthStatus(401))
        assertTrue(ErrorMapper.isAuthStatus(403))
        assertFalse("429 is quota, not authentication", ErrorMapper.isAuthStatus(429))
        assertFalse(ErrorMapper.isAuthStatus(200))
        assertTrue(ErrorMapper.isServerError(500))
        assertFalse(ErrorMapper.isServerError(429))
    }

    @Test
    fun `webview error codes become human cases`() {
        assertEquals(ErrorKind.NO_NETWORK, ErrorMapper.fromWebViewErrorCode(ErrorMapper.ERROR_HOST_LOOKUP, null))
        assertEquals(ErrorKind.TIMEOUT, ErrorMapper.fromWebViewErrorCode(ErrorMapper.ERROR_TIMEOUT, "timed out"))
        assertEquals(ErrorKind.TLS, ErrorMapper.fromWebViewErrorCode(ErrorMapper.ERROR_FAILED_SSL_HANDSHAKE, null))
        assertEquals(ErrorKind.PARSE, ErrorMapper.fromWebViewErrorCode(ErrorMapper.ERROR_BAD_URL, "bad url"))
        assertEquals(ErrorKind.BLOCKED_ORIGIN, ErrorMapper.fromWebViewErrorCode(ErrorMapper.ERROR_UNSAFE_URL, null))
        // An IO failure whose message mentions a timeout is a timeout, and nothing else.
        assertEquals(ErrorKind.TIMEOUT, ErrorMapper.fromWebViewErrorCode(ErrorMapper.ERROR_CONNECT, "read timed out"))
        assertEquals(ErrorKind.SERVER, ErrorMapper.fromWebViewErrorCode(ErrorMapper.ERROR_CONNECT, "connection reset"))
        assertEquals(ErrorKind.SERVER, ErrorMapper.fromWebViewErrorCode(-999, "mystery"))
    }

    @Test
    fun `exceptions are classified without ever leaking the stack trace`() {
        assertEquals(ErrorKind.TIMEOUT, ErrorMapper.fromThrowable(SocketTimeoutException("timeout")))
        assertEquals(ErrorKind.NO_NETWORK, ErrorMapper.fromThrowable(java.net.UnknownHostException("arena.ai")))
        assertEquals(
            ErrorKind.NO_NETWORK,
            ErrorMapper.fromThrowable(IllegalStateException("Network is no longer available")),
        )
    }

    @Test
    fun `every error carries a user action and no stack trace`() {
        val error = ErrorKind.NO_NETWORK.toError()
        assertEquals(ErrorKind.NO_NETWORK.title, error.title)
        assertTrue(error.retryable)
        assertTrue(error.actionLabel.isNotBlank())
        assertFalse(error.message.contains("Exception"))
        assertFalse(error.message.contains("\tat "))

        val tls = ErrorKind.TLS.toError("handshake failed for arena.ai:443")
        assertEquals(ErrorKind.TLS.title, tls.title)
        assertEquals("handshake failed for arena.ai:443", tls.detail)
        assertTrue(tls.detail?.contains("arena.ai") == true)
    }

    @Test
    fun `rate limit copy names the account and model and never claims a reset time`() {
        val copy = ErrorMapper.rateLimitCopy("Account 2", "Claude Opus", retryAfterSeconds = null)
        assertTrue("must name the account: $copy", copy.contains("Account 2"))
        assertTrue("must name the model: $copy", copy.contains("Claude Opus"))
        assertTrue("must say the reset window is unknown: $copy", copy.contains("did not report a reset time"))
        assertFalse(copy.contains("5x"))
        assertFalse(copy.contains("five times"))

        // And when the service *did* expose a window, it is reported as an approximation.
        val withWindow = ErrorMapper.rateLimitCopy("Account 2", null, retryAfterSeconds = 90)
        assertTrue(withWindow.contains("90"))
    }

    @Test
    fun `no ErrorKind has empty user-facing copy`() {
        ErrorKind.entries.forEach { kind ->
            assertTrue("$kind title", kind.title.isNotBlank())
            assertTrue("$kind message", kind.userMessage.isNotBlank())
            assertTrue("$kind action", kind.actionLabel.isNotBlank())
            assertFalse("$kind must not mention exceptions", kind.userMessage.contains("Exception"))
            val error = AppError(kind, null)
            assertEquals(kind.title, error.title)
            assertEquals(kind.userMessage, error.message)
            assertEquals(kind.actionLabel, error.actionLabel)
        }
    }
}

class RedactionTest {

    @Test
    fun `cookie headers are masked`() {
        val masked = AppLog.redact("Cookie: sid=abc123def456; theme=dark")
        assertFalse(masked.contains("abc123def456"))
        assertTrue(masked.contains("<redacted>"))
    }

    @Test
    fun `bearer tokens and api keys are masked`() {
        assertFalse(AppLog.redact("Authorization: Bearer abcdef123456").contains("abcdef123456"))
        assertFalse(AppLog.redact("token=sk_live_023940239").contains("sk_live_023940239"))
        assertFalse(AppLog.redact("api_key=1234567890abcdef").contains("1234567890abcdef"))
    }

    @Test
    fun `json secrets in a quoted body are masked`() {
        val masked = AppLog.redact("""{"password": "hunter2", "email": "me@corp.example"}""")
        assertFalse(masked.contains("hunter2"))
        assertFalse(masked.contains("me@corp.example"))
    }

    @Test
    fun `a JWT is replaced wholesale`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N"
        assertFalse(AppLog.redact("session token $jwt found").contains("eyJzdWIi"))
    }

    @Test
    fun `ordinary text is left alone`() {
        val text = "Loaded https://arena.ai/leaderboard in 812ms"
        assertEquals(text, AppLog.redact(text))
    }

    @Test
    fun `diagnose keeps only the type and a redacted message`() {
        val line = AppLog.diagnose(IllegalStateException("Cookie: sid=secretvalue for me@corp.example"))
        assertFalse(line.contains("secretvalue"))
        assertFalse(line.contains("me@corp.example"))
        assertTrue(line.contains("IllegalStateException"))
        assertTrue(line.length <= 160)
    }

    @Test
    fun `cookie summaries are name and length only`() {
        val entries = listOf(
            com.kartikkamboj.arena.data.model.CookieEntry("sid", "super-secret-value"),
            com.kartikkamboj.arena.data.model.CookieEntry("theme", "dark"),
        )
        val summary = CookieJarCodec.summarise(entries)
        assertFalse(summary.contains("super-secret-value"))
        assertTrue(summary.contains("sid("))
    }
}
