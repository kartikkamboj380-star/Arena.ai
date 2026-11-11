package com.kartikkamboj.arena.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Origin recognition and download naming — the two pieces of [ArenaSite] that touch user data. */
class ArenaSiteTest {

    @Test
    fun `only the site's own https origins are trusted`() {
        assertTrue(ArenaSite.isTrusted("https://arena.ai/"))
        assertTrue(ArenaSite.isTrusted("https://www.arena.ai/leaderboard"))
        assertFalse("cleartext is never trusted", ArenaSite.isTrusted("http://arena.ai/"))
        assertFalse(ArenaSite.isTrusted("https://arena.ai.example.com/"))
        assertFalse(ArenaSite.isTrusted("https://notarena.ai/"))
        assertFalse(ArenaSite.isTrusted(null))
        assertFalse(ArenaSite.isTrusted(""))
        assertFalse(ArenaSite.isTrusted("arena.ai/leaderboard"))
    }

    @Test
    fun `the provider list is narrow and exact`() {
        assertTrue(ArenaSite.isAuthPage("https://accounts.google.com/v3/signin"))
        assertFalse(ArenaSite.isAuthPage("https://evil.example/accounts.google.com"))
        assertFalse(ArenaSite.isAuthPage("https://google.com/"))
        assertFalse(ArenaSite.isAuthPage("https://docs.google.com/document/x"))
    }

    @Test
    fun `a host is shown only when it really is a host`() {
        assertEquals("accounts.google.com", ArenaSite.displayHost("https://accounts.google.com/x"))
        assertNull(ArenaSite.displayHost("not a url"))
        assertNull(ArenaSite.displayHost(null))
    }

    @Test
    fun `a content disposition name wins and travels unharmed`() {
        assertEquals(
            "report.pdf",
            ArenaSite.suggestedFileName("attachment; filename=\"report.pdf\"", "https://arena.ai/f", null),
        )
        assertEquals(
            "report.pdf",
            ArenaSite.suggestedFileName("inline; filename=report.pdf; size=1", "https://arena.ai/f", null),
        )
    }

    @Test
    fun `the extended form is percent-decoded`() {
        assertEquals(
            "quarterly report.pdf",
            ArenaSite.suggestedFileName("attachment; filename*=UTF-8''quarterly%20report.pdf", "https://arena.ai/f", null),
        )
    }

    @Test
    fun `a path in a filename cannot escape the destination directory`() {
        val name = ArenaSite.suggestedFileName(
            "attachment; filename=\"../../../../../../etc/passwd\"",
            "https://arena.ai/f",
            null,
        )
        // The leaf survives, the path does not; a server-named file keeps its name even without one.
        assertEquals("passwd", name)
        assertFalse(name!!.contains("/"))
        assertFalse(name.contains(".."))
    }

    @Test
    fun `with no header the last path segment is used`() {
        assertEquals("shot.png", ArenaSite.suggestedFileName(null, "https://arena.ai/files/shot.png", "image/png"))
        assertEquals(
            "chart.svg",
            ArenaSite.suggestedFileName(null, "https://arena.ai/render/chart?size=large", "image/svg+xml"),
        )
    }

    @Test
    fun `a name with no extension gets one from the mime type, or none at all`() {
        assertEquals("chart.png", ArenaSite.suggestedFileName(null, "https://arena.ai/files/chart", "image/png"))
        assertNull("no name anywhere is answered with no name", ArenaSite.suggestedFileName(null, "https://arena.ai/", null))
    }

    @Test
    fun `mime extensions cover what the site serves and nothing exotic`() {
        assertEquals("png", MimeTypeExtensions.of("image/png"))
        assertEquals("md", MimeTypeExtensions.of("text/markdown; charset=utf-8"))
        assertEquals("pdf", MimeTypeExtensions.of("application/pdf"))
        assertNull(MimeTypeExtensions.of("application/vnd.oasis.opendocument.text"))
        assertNull(MimeTypeExtensions.of(null))
    }

    @Test
    fun `the entry point and the cookie origin are the same site`() {
        assertEquals("https://arena.ai/", ArenaSite.HOME)
        assertEquals("https://arena.ai", ArenaSite.COOKIE_URL)
        assertTrue(ArenaSite.TRUSTED_HOSTS.containsAll(setOf("arena.ai", "www.arena.ai")))
    }
}
