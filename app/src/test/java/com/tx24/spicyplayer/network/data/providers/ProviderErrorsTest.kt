package com.tx24.spicyplayer.network.data.providers

import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderErrorsTest {
    @Test fun kuwoSearchParsesNestedSingleQuotedRecords() {
        val raw = "{'HIT':'1','abslist':[{'ARTIST':'Jay&nbsp;Chou','DURATION':'269','MUSICRID':'MUSIC_1'," +
            "'SONGNAME':'Qing&nbsp;Tian','audiobookpayinfo':{'download':'0'}}]}"
        assertEquals(listOf(KuwoSong("Qing Tian", "Jay Chou", 269, "MUSIC_1")), kuwoSearchResults(raw))
    }

    @Test fun kuwoLyricsAreGb18030() {
        val bytes = byteArrayOf(0x84.toByte(), 0x31, 0x95.toByte(), 0x33) + "[00:01.00]晴天\r\n".toByteArray(charset("GB18030"))
        assertEquals("[00:01.00]晴天\n", decodeKuwoLrc(bytes))
    }

    @Test fun httpFailuresKeepTheirActualCategory() {
        assertEquals(ProviderFailureCategory.SERVER, ProviderHttpException("QQ", 500).unavailable().category)
        assertEquals(ProviderFailureCategory.AUTHENTICATION, ProviderHttpException("Genius", 401).unavailable().category)
    }
}
