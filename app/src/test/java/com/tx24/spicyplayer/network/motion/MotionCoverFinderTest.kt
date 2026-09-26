package com.tx24.spicyplayer.network.motion

import com.tx24.spicyplayer.network.motion.MotionCoverFinder.AlbumHit
import com.tx24.spicyplayer.network.motion.MotionCoverFinder.Companion.albumIds
import com.tx24.spicyplayer.network.motion.MotionCoverFinder.Companion.ambientVideo
import com.tx24.spicyplayer.network.motion.MotionCoverFinder.Companion.key
import com.tx24.spicyplayer.network.motion.MotionCoverFinder.Companion.loose
import com.tx24.spicyplayer.network.motion.MotionCoverFinder.Companion.parseAlbums
import com.tx24.spicyplayer.network.motion.MotionCoverFinder.Companion.plain
import com.tx24.spicyplayer.network.motion.MotionCoverFinder.Companion.rankAlbums
import com.tx24.spicyplayer.ui.nowplaying.leadArtist
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MotionCoverFinderTest {
    @Test
    fun `edition suffixes come off`() {
        assertEquals("Midnights", plain("Midnights (The Til Dawn Edition)"))
        assertEquals("Blinding Lights", plain("Blinding Lights - Single"))
        assertEquals("Folklore", plain("Folklore (Deluxe Version)"))
        assertEquals("Hybrid Theory", plain("Hybrid Theory"))
    }

    @Test
    fun `loose names allow editions either way round`() {
        assertTrue(loose("Midnights", "Midnights (3am Edition)"))
        assertTrue(loose("Midnights (3am Edition)", "Midnights"))
        assertFalse(loose("NO NAME", "Something Else"))
        // Short names must match exactly, not by containment.
        assertFalse(loose("NF", "Jack White NF"))
    }

    @Test
    fun `another artist's album of the same name is never picked`() {
        val hits = listOf(
            AlbumHit("https://music.apple.com/us/album/no-name/1", "No Name", "Jack White"),
            AlbumHit("https://music.apple.com/us/album/no-name/2", "NO NAME", "NF"),
        )
        assertEquals(listOf("https://music.apple.com/us/album/no-name/2"), rankAlbums(hits, "NF", "NO NAME"))
    }

    @Test
    fun `exact name ranks above an edition`() {
        val hits = listOf(
            AlbumHit("u/deluxe", "Midnights (3am Edition)", "Taylor Swift"),
            AlbumHit("u/plain", "Midnights", "Taylor Swift"),
            AlbumHit("u/plain", "Midnights", "Taylor Swift"),
        )
        assertEquals(listOf("u/plain", "u/deluxe"), rankAlbums(hits, "Taylor Swift", "Midnights"))
    }

    @Test
    fun `search results parse and skip non-albums`() {
        val json = """{"results":[
            {"wrapperType":"collection","collectionName":"Midnights","artistName":"Taylor Swift",
             "collectionViewUrl":"https://music.apple.com/us/album/midnights/123?uo=4"},
            {"wrapperType":"artist","artistName":"Taylor Swift"}]}"""
        assertEquals(
            listOf(AlbumHit("https://music.apple.com/us/album/midnights/123", "Midnights", "Taylor Swift")),
            parseAlbums(json),
        )
        assertEquals(emptyList<AlbumHit>(), parseAlbums("not json"))
    }

    @Test
    fun `album ids and the ambient video come out of the pages`() {
        val search = """<a href="https://music.apple.com/us/album/from-zero/1766137049">
            <a href="https://music.apple.com/us/album/from-zero/1766137049"><a href="/us/album/x/5">"""
        assertEquals(listOf("1766137049"), albumIds(search))
        val page = """<amp-ambient-video class="x" src="https://mvod.itunes.apple.com/a/P1_default.m3u8" loop>"""
        assertEquals("https://mvod.itunes.apple.com/a/P1_default.m3u8", ambientVideo(page))
        assertNull(ambientVideo("<img src=\"cover.jpg\">"))
    }

    @Test
    fun `a single is keyed by its title`() {
        assertEquals(key("NF", "", "HOPE"), key("nf", "", "Hope"))
        assertNull(key("", "Album", "Title"))
    }

    @Test
    fun `lead artist drops the rest of the credit`() {
        assertEquals("Drake", leadArtist("Drake, 21 Savage"))
        assertEquals("Calvin Harris", leadArtist("Calvin Harris feat. Rihanna"))
        assertEquals("Simon & Garfunkel", leadArtist("Simon & Garfunkel"))
    }

    @Test
    fun `live lookup finds a known animated cover`() {
        assumeTrue(System.getenv("RUN_MOTION_COVER_TEST") == "1")
        val finder = MotionCoverFinder(OkHttpClient(), memoFile = null)
        val url = runBlocking { finder.find("Taylor Swift", "Midnights", "Anti-Hero") }
        assertNotNull(url)
        assertTrue(url!!.endsWith(".m3u8"))
    }
}
