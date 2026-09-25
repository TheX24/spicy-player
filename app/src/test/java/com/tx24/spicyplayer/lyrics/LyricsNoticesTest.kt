package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.network.data.ProviderAttempt
import com.tx24.spicyplayer.network.data.ProviderAttemptOutcome
import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsNoticesTest {
    private fun notice(vararg attempts: ProviderAttempt) = LyricsNotices.noLyrics(attempts.toList()) { it.uppercase() }

    @Test fun oneFailingSourceAmongMissesIsStillNoLyrics() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.MISS),
            ProviderAttempt("netease", ProviderAttemptOutcome.MISS),
            ProviderAttempt("musixmatch", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.SERVER, message = "Musixmatch HTTP 503"),
        )
        assertEquals("We don't have any lyrics for this song", error.message)
        assertEquals("2 sources had none. Couldn't check MUSIXMATCH (HTTP 503)", error.detail)
    }

    @Test fun everySourceOfflineAsksToGoOnline() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.NETWORK),
            ProviderAttempt("spicy_lyrics", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.TIMEOUT),
        )
        assertEquals("Please go online to enjoy your lyrics experience!", error.message)
        assertEquals("Couldn't reach LRCLIB (no connection), SPICY_LYRICS (timed out)", error.detail)
    }

    @Test fun skippedAndDisabledSourcesDontCount() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.MISS),
            ProviderAttempt("qq", ProviderAttemptOutcome.DISABLED),
        )
        assertEquals("Checked 1 source, none had it", error.detail)
    }
}
