package com.fitnessapp.summary

import com.fitnessapp.summary.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The updater's fallback path. `api.github.com` allows 60 unauthenticated requests an hour per
 * IP, and a mobile carrier shares one IP between thousands of subscribers - so the button can
 * fail on its first press. The release feed is not the API and is not counted against that
 * quota, but it knows only tags, which is exactly what the version check needs.
 */
class UpdateCheckerTest {

    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <id>tag:github.com,2008:Repository/1/build-41</id>
            <link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/build-41"/>
            <title>Build 41</title>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1/build-43</id>
            <link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/build-43"/>
            <title>Build 43</title>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1/build-42</id>
            <link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/build-42"/>
            <title>Build 42</title>
          </entry>
        </feed>
    """.trimIndent()

    /**
     * Highest number, not first entry. The feed is newest-first today, and depending on that
     * would be depending on a promise nobody made - a re-published release reorders it.
     */
    @Test
    fun `the newest release is the highest build number, whatever order the feed is in`() {
        assertEquals("build-43", UpdateChecker.newestTag(atom))
    }

    @Test
    fun `a feed without releases says so instead of naming a wrong version`() {
        assertNull(UpdateChecker.newestTag("<feed><title>Releases</title></feed>"))
    }

    /** Version numbers are not compared as text: 100 comes after 99, not before it. */
    @Test
    fun `build numbers are compared as numbers`() {
        val feed = "build-99 build-100 build-9"
        assertEquals("build-100", UpdateChecker.newestTag(feed))
    }

    /**
     * GitHub names the exact second the quota returns, and saying it turns "попробуйте позже"
     * into something the user can act on.
     */
    @Test
    fun `the rate limit reset time is named when GitHub sends it`() {
        val note = UpdateChecker.rateLimitNote("1789000000", ZoneId.of("UTC"))
        assertTrue(note, note.contains(":"))
        assertTrue(note, note.contains("лимит сбросится"))
    }

    @Test
    fun `a missing or broken reset header still leaves a readable sentence`() {
        assertEquals(" — попробуйте позже.", UpdateChecker.rateLimitNote(null))
        assertEquals(" — попробуйте позже.", UpdateChecker.rateLimitNote("не число"))
        assertEquals(" — попробуйте позже.", UpdateChecker.rateLimitNote("0"))
    }
}
