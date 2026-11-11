package com.kartikkamboj.arena.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Origin recognition and download naming — the two pieces of [ArenaSite] that touch user data. */
class ArenaSiteTest {

    @Test
    fun `the family rule accepts only https and rejects every shape of lookalike`() {
        assertTrue(ArenaSite.isSiteFamily("https://arena.ai/"))
        assertTrue(ArenaSite.isSiteFamily("https://www.arena.ai/leaderboard"))
        assertFalse("cleartext is never trusted", ArenaSite.isSiteFamily("http://arena.ai/"))
        assertFalse(ArenaSite.isSiteFamily("https://arena.ai.example.com/"))
        assertFalse(ArenaSite.isSiteFamily("https://notarena.ai/"))
        assertFalse(ArenaSite.isSiteFamily(null))
        assertFalse(ArenaSite.isSiteFamily(""))
        assertFalse(ArenaSite.isSiteFamily("arena.ai/leaderboard"))
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
        assertEquals("https://arena.ai/", ArenaSite.HOME)
        assertEquals("arena.ai", ArenaSite.SITE_DOMAIN)
        assertTrue(ArenaSite.isSiteFamily(ArenaSite.HOME))
        assertTrue(ArenaSite.isSiteFamily(ArenaSite.COOKIE_URL.replace("https://", "https://www.")))
    }

    // --- who counts as "the site", and what may be written down about a url -------------------

    @Test
    fun `the auth subdomain is the site, which is the whole point of the family rule`() {
        assertTrue("auth lives on a subdomain", ArenaSite.isSiteFamily("https://auth.arena.ai/login"))
        assertTrue(ArenaSite.isSiteFamily("https://www.arena.ai/battle"))
        assertTrue(ArenaSite.isSiteFamily("https://arena.ai/"))
        assertFalse(ArenaSite.isSiteFamily("https://arena.ai.evil.net/"))
        assertFalse(ArenaSite.isSiteFamily("https://notarena.ai/"))
        assertFalse("http is not the site, it is a downgrade", ArenaSite.isSiteFamily("http://arena.ai/"))
        assertFalse(ArenaSite.isSiteFamily("https://.arena.ai/"))
    }

    @Test
    fun `the provider is the provider and not the page it links to`() {
        assertTrue(ArenaSite.isProviderPage("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(ArenaSite.isProviderPage("https://myaccount.google.com/x"))
        assertTrue(ArenaSite.isProviderPage("https://oauth2.googleapis.com/device/code"))
        assertFalse(ArenaSite.isProviderPage("https://accounts.google.com.evil.net/x"))
        assertFalse(ArenaSite.isProviderPage("https://arena.ai/x"))
        assertTrue("the published endpoints are a subset of the provider's pages", ArenaSite.isAuthPage("https://accounts.google.com/x"))
        assertFalse("docs.google.com is a product, not a sign-in screen", ArenaSite.isAuthPage("https://docs.google.com/x"))
    }

    @Test
    fun `a url in a log line is a host and a path, never a query`() {
        val line = ArenaSite.forLog("https://auth.arena.ai/api/auth/callback/google?code=SECRET&state=abc&id_token=eyJhbGci")
        assertEquals("auth.arena.ai/api/auth/callback/google", line)
        assertFalse(line.contains("SECRET"))
        assertFalse(line.contains("code"))
        assertFalse(line.contains("id_token"))
    }

    @Test
    fun `a log line for a bare origin does not end in a slash`() {
        assertEquals("arena.ai", ArenaSite.forLog("https://arena.ai/"))
        assertEquals("an unparseable url", ArenaSite.forLog("not a url at all"))
        assertEquals("an unparseable url", ArenaSite.forLog(null))
    }
    @Test
    fun `the oauth trail reports parameter names and never a value`() {
        val url = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?response_type=code&client_id=123.apps.googleusercontent.com&redirect_uri=" +
            "https://arena.ai/api/auth/callback/google&scope=openid%20email&state=SECRETSTATE" +
            "&nonce=SECRETNONCE&ux_mode=popup&unlisted=whatever"
        val names = ArenaSite.oauthParameterNames(url)
        // Sorted and deduplicated, so the same flow always produces the same line to compare against.
        assertEquals("client_id,nonce,redirect_uri,response_type,scope,state,ux_mode", names)
        assertFalse("a value must never reach the log", names.contains("SECRETSTATE"))
        assertFalse("a value must never reach the log", names.contains("SECRETNONCE"))
        assertFalse("a path inside a value is not reportable either", names.contains("arena.ai"))
        assertFalse("only the allow-listed names are reportable", names.contains("unlisted"))
    }

    @Test
    fun `a url with nothing reportable gives an empty trail, not a guess`() {
        assertEquals("", ArenaSite.oauthParameterNames("https://arena.ai/battle"))
        assertEquals("", ArenaSite.oauthParameterNames("https://accounts.google.com/gis_transform?bsv=7"))
        assertEquals("", ArenaSite.oauthParameterNames(null))
        assertEquals("", ArenaSite.oauthParameterNames("https://accounts.google.com/x#state=SECRETSTATE"))
        assertEquals(
            "a fragment is not a query, and an error name is worth reporting",
            "error",
            ArenaSite.oauthParameterNames("https://accounts.google.com/x?error=invalid_request#state=S"),
        )
    }

}
