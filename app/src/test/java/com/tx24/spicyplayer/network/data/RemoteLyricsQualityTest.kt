package com.tx24.spicyplayer.network.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteLyricsQualityTest {
    @Test
    fun `malformed ttml without fallback has no quality`() {
        assertEquals(
            RemoteLyricsQuality.NONE,
            RemoteLyricsPayload(ttmlLyrics = "broken").measuredQuality(),
        )
    }

    @Test
    fun `word timed ttml is word synced`() {
        val payload = RemoteLyricsPayload(
            ttmlLyrics = """
                <tt xmlns="http://www.w3.org/ns/ttml">
                  <body><div><p begin="1s" end="3s">
                    <span begin="1s" end="2s">hello</span>
                    <span begin="2s" end="3s">world</span>
                  </p></div></body>
                </tt>
            """.trimIndent(),
        )

        assertEquals(RemoteLyricsQuality.WORD_SYNCED, payload.measuredQuality())
    }

    @Test
    fun `line timed ttml is line synced`() {
        val payload = RemoteLyricsPayload(
            ttmlLyrics = """
                <tt xmlns="http://www.w3.org/ns/ttml">
                  <body><div><p begin="1s" end="3s">hello world</p></div></body>
                </tt>
            """.trimIndent(),
        )

        assertEquals(RemoteLyricsQuality.LINE_SYNCED, payload.measuredQuality())
    }

    @Test
    fun `untimed ttml is plain`() {
        val payload = RemoteLyricsPayload(
            ttmlLyrics = """
                <tt xmlns="http://www.w3.org/ns/ttml">
                  <body><div><p>hello world</p></div></body>
                </tt>
            """.trimIndent(),
        )

        assertEquals(RemoteLyricsQuality.PLAIN, payload.measuredQuality())
    }

    @Test
    fun `valid fallback wins when ttml is malformed`() {
        assertEquals(
            RemoteLyricsQuality.LINE_SYNCED,
            RemoteLyricsPayload(
                ttmlLyrics = "<tt>",
                syncedLyrics = "[00:01.00]hello",
                plainLyrics = "hello",
            ).measuredQuality(),
        )
    }

    @Test
    fun `blank representations have no quality`() {
        assertEquals(
            RemoteLyricsQuality.NONE,
            RemoteLyricsPayload("  ", "\n", "\t").measuredQuality(),
        )
    }

    @Test
    fun `untimed text in the synced field is plain`() {
        val payload = RemoteLyricsPayload(syncedLyrics = "First line\nSecond line\n\nThird line").withoutFakeTiming()

        assertEquals(null, payload.syncedLyrics)
        assertEquals("First line\nSecond line\n\nThird line", payload.plainLyrics)
        assertEquals(RemoteLyricsQuality.PLAIN, payload.measuredQuality())
    }

    @Test
    fun `every line on one stamp is plain, without tags or stamps`() {
        val payload = RemoteLyricsPayload(
            syncedLyrics = "[ar:rosa]\n[01:50.00]First line\n[01:50.00]Second line\n[01:50.00]\n[01:50.00]Third line",
        ).withoutFakeTiming()

        assertEquals(null, payload.syncedLyrics)
        assertEquals("First line\nSecond line\n\nThird line", payload.plainLyrics)
    }

    @Test
    fun `fake timing keeps the source's own plain lyrics`() {
        val payload = RemoteLyricsPayload(plainLyrics = "Real plain", syncedLyrics = "Untimed").withoutFakeTiming()

        assertEquals("Real plain", payload.plainLyrics)
    }

    @Test
    fun `real timing is left alone`() {
        val payload = RemoteLyricsPayload(syncedLyrics = "[ti:x]\n[00:01.00]First\n[00:03.50]Second")

        assertEquals(payload, payload.withoutFakeTiming())
        assertEquals(RemoteLyricsQuality.LINE_SYNCED, payload.withoutFakeTiming().measuredQuality())
    }
}
