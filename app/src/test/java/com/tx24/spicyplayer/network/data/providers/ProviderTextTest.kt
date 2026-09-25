package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.isNoWordsNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTextTest {
    @Test fun instrumentalNoteIsNotLyrics() {
        assertTrue(RemoteLyricsPayload(syncedLyrics = "[00:00.00]作词 : 无\n[00:01.00]纯音乐，请欣赏").isNoWordsNote())
        assertTrue(RemoteLyricsPayload(plainLyrics = "此歌曲为没有填词的纯音乐，请您欣赏").isNoWordsNote())
    }

    @Test fun realLyricsMentioningTheWordsAreKept() {
        val song = (1..6).joinToString("\n") { "[00:0$it.00]line $it" } + "\n[00:07.00]纯音乐"
        assertFalse(RemoteLyricsPayload(syncedLyrics = song).isNoWordsNote())
    }

    @Test fun richsyncSpacesSeparateWordsAndEndThem() {
        val rows = Gson().fromJson(
            """[{"ts":10.0,"te":12.0,"l":[{"c":"Hel","o":0.0},{"c":"lo","o":0.3},{"c":" ","o":0.6},{"c":"there","o":1.0}]}]""",
            JsonArray::class.java,
        )
        val ttml = RichSyncToTtml.convert(rows)!!
        assertTrue(ttml, ttml.contains("""<span begin="10.000s" end="10.300s">Hel</span><span begin="10.300s" end="10.600s">lo</span> <span begin="11.000s" end="12.000s">there</span>"""))
    }

    @Test fun geniusSkipsNestedPageFurniture() {
        val html = """<div data-lyrics-container="true" class="x"><div data-exclude-from-selection="true"><div><span>Translations</span></div></div>""" +
            """[Verse 1]<br/>We're no <a href="#"><span>strangers</span></a> to love<br>You know the rules</div><div>footer</div>"""
        assertEquals("[Verse 1]\nWe're no strangers to love\nYou know the rules", geniusLyricsText(html))
    }
}
