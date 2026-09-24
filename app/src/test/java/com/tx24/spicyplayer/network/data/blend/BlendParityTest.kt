package com.tx24.spicyplayer.network.data.blend

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The port against mild-lyrics itself: real base and donor documents, and the blend its Python
 * made of them. Fixtures are full song lyrics, so they are not checked in; generate them with
 * `tools/make_blend_fixtures.py` (needs the mild-lyrics reference checkout) into
 * `build/blend-fixtures`, or point BLEND_FIXTURES_DIR elsewhere. Skipped when absent.
 */
class BlendParityTest {
    private val dir = System.getenv("BLEND_FIXTURES_DIR")?.let(::File)
        ?: File(System.getProperty("user.dir")).resolve("../build/blend-fixtures")

    @Test
    fun `blends match mild-lyrics on real documents`() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assumeTrue("no blend fixtures in $dir", files.isNotEmpty())
        val failures = mutableListOf<String>()
        var compared = 0
        var lines = 0
        var syllables = 0
        for (file in files) {
            val fixture = JsonParser.parseString(file.readText()).asJsonObject
            val title = fixture["meta"].asJsonObject["title"].asString
            val base = doc(fixture["base"].asJsonObject)
            val donors = fixture["donors"].asJsonObject
            fun donor(id: String?, name: String) = id?.let { BlendDonor(donors[it]?.takeIf { d -> d.isJsonObject }?.let { d -> doc(d.asJsonObject) }, name, it) }
            for ((blendId, expected) in fixture["expected"].asJsonObject.entrySet()) {
                val (timingId, spareId) = BLENDS.getValue(blendId)
                val got = LyricsBlender.blended(
                    // A fresh base each time: the blend writes into the lines it builds, never into its inputs,
                    // but the comparison should not have to take that on trust.
                    doc(fixture["base"].asJsonObject), fixture["words"].asString, fixture["origin"].asString,
                    donor(timingId, NAMES.getValue(timingId))!!,
                    donor(spareId, spareId?.let(NAMES::getValue).orEmpty()),
                )
                compared++
                val where = "$title / $blendId"
                if (expected.isJsonNull) {
                    if (got != null) failures += "$where: expected nothing, got ${got.via ?: got.alone}"
                    continue
                }
                val want = expected.asJsonObject
                if (got == null) { failures += "$where: got nothing"; continue }
                val wantVia = want["_via"]?.asString
                val wantAlone = want["_alone"]?.asString
                if (wantVia != got.via || (wantVia == null && wantAlone != got.alone)) {
                    failures += "$where: made of ${got.via ?: got.alone}, mild-lyrics ${wantVia ?: wantAlone}"
                }
                failures += compare(where, doc(want).lines, got.doc.lines).take(5)
                lines += got.doc.lines.size
                syllables += got.doc.lines.sumOf { it.lead?.syllables.orEmpty().size + it.background.sumOf { g -> g.syllables.size } }
            }
            check(base.lines.isNotEmpty())
        }
        println("Compared $compared blends over ${files.size} songs ($lines lines, $syllables syllables); ${failures.size} differences")
        failures.forEach(::println)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `every real blend reads back through the TTML parser as it was written`() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assumeTrue("no blend fixtures in $dir", files.isNotEmpty())
        val failures = mutableListOf<String>()
        for (file in files) {
            val fixture = JsonParser.parseString(file.readText()).asJsonObject
            for ((blendId, expected) in fixture["expected"].asJsonObject.entrySet()) {
                if (!expected.isJsonObject) continue
                val doc = doc(expected.asJsonObject)
                val quality = BlendText.quality(doc)
                if (quality != BlendQuality.SYLLABLE) continue
                val parsed = com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser.parse(
                    BlendDocuments.toTtml(doc, quality).byteInputStream())
                val where = "${file.nameWithoutExtension} / $blendId"
                val leads = parsed.lines.filter { it.role == com.tx24.spicyplayer.lyrics.spicy.models.LineRole.LEAD }
                val backs = parsed.lines.count { it.role == com.tx24.spicyplayer.lyrics.spicy.models.LineRole.BACKGROUND }
                if (leads.size != doc.lines.size) { failures += "$where: ${leads.size} lines, wrote ${doc.lines.size}"; continue }
                if (backs != doc.lines.sumOf { l -> l.background.count { it.syllables.isNotEmpty() } }) failures += "$where: $backs backing groups"
                for ((line, read) in doc.lines.zip(leads)) {
                    val syls = line.lead?.syllables.orEmpty()
                    if (syls.isEmpty()) continue
                    val words = read.words
                    val ok = words.size == syls.size && words.zip(syls).withIndex().all { (i, p) ->
                        val (w, s) = p
                        w.text == s.text.trim() && abs(w.startMs - s.start * 1000) <= 1 &&
                            (i == 0 || w.isPartOfWord == syls[i - 1].partOfWord)
                    }
                    if (!ok) failures += "$where: \"${BlendText.lineText(line)}\" read back as ${words.map { it.text }}"
                }
            }
        }
        failures.take(20).forEach(::println)
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
    }

    private fun compare(where: String, want: List<BlendLine>, got: List<BlendLine>): List<String> {
        val out = mutableListOf<String>()
        if (want.size != got.size) out += "$where: ${got.size} lines, mild-lyrics ${want.size}"
        for ((i, pair) in want.zip(got).withIndex()) {
            val (w, g) = pair
            val at = "$where line $i \"${BlendText.lineText(w)}\""
            if (BlendText.lineText(w) != BlendText.lineText(g)) out += "$at: text \"${BlendText.lineText(g)}\""
            if (!same(w.start, g.start) || !same(w.end, g.end)) out += "$at: ${g.start}-${g.end}, mild-lyrics ${w.start}-${w.end}"
            syllables(w.lead?.syllables.orEmpty(), g.lead?.syllables.orEmpty())?.let { out += "$at lead: $it" }
            if (w.background.size != g.background.size) out += "$at: ${g.background.size} backing groups, mild-lyrics ${w.background.size}"
            for ((k, groups) in w.background.zip(g.background).withIndex()) {
                val (wg, gg) = groups
                if (!same(wg.start, gg.start) || !same(wg.end, gg.end)) out += "$at bg $k: ${gg.start}-${gg.end}, mild-lyrics ${wg.start}-${wg.end}"
                syllables(wg.syllables, gg.syllables)?.let { out += "$at bg $k: $it" }
            }
        }
        return out
    }

    private fun syllables(want: List<BlendSyllable>, got: List<BlendSyllable>): String? {
        if (want.size != got.size) return "${got.map { it.text }} vs mild-lyrics ${want.map { it.text }}"
        for ((w, g) in want.zip(got)) {
            if (w.text != g.text || !same(w.start, g.start) || !same(w.end, g.end) || w.partOfWord != g.partOfWord || w.guess != g.guess) {
                return "$g vs mild-lyrics $w"
            }
        }
        return null
    }

    private fun same(a: Double?, b: Double?) = if (a == null || b == null) a == b else abs(a - b) < 1e-6

    private fun doc(json: JsonObject): BlendDoc {
        val items = (json["Content"] ?: json["Lines"]).asJsonArray
        return BlendDoc(items.map { el ->
            val it = el.asJsonObject
            BlendLine(
                text = it["Text"]?.asString,
                start = it.num("StartTime"),
                end = it.num("EndTime"),
                lead = it["Lead"]?.takeIf { l -> l.isJsonObject }?.asJsonObject?.let(::group),
                background = it["Background"]?.asJsonArray?.map { g -> group(g.asJsonObject) }.orEmpty(),
            )
        })
    }

    private fun group(json: JsonObject) = BlendGroup(
        json["Syllables"].asJsonArray.map { el ->
            val y = el.asJsonObject
            BlendSyllable(y["Text"].asString, y["StartTime"].asDouble, y["EndTime"].asDouble,
                y["IsPartOfWord"]?.asBoolean == true, y["Guess"]?.asBoolean == true)
        },
        json.num("StartTime"),
        json.num("EndTime"),
    )

    private fun JsonObject.num(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asDouble

    private companion object {
        val BLENDS = mapOf(
            "blend_qq" to ("qq" to null),
            "blend_kugou" to ("kugou" to null),
            "blend_netease" to ("netease" to null),
            "blend_netease_qq" to ("netease" to "qq"),
            "blend_netease_kugou" to ("netease" to "kugou"),
        )
        val NAMES = mapOf("qq" to "QQ Music", "kugou" to "Kugou", "netease" to "NetEase")
    }
}
