package com.tx24.spicyplayer.translation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.data.providers.UnisonLyricsProvider
import java.io.IOException
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class UnisonTranslator(private val client: OkHttpClient) : Translator {
    override val provider = TranslationProvider.Unison
    override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
        val body = JsonObject().apply {
            add("lines", com.google.gson.JsonArray().apply { request.lines.forEach(::add) })
            addProperty("to", request.target)
            request.source?.let { addProperty("from", it) }
        }
        val response = client.newCall(Request.Builder().url("${UnisonLyricsProvider.BASE}/translate")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
            .awaitResponse().use { response ->
                // 400: no language found in these lines; 502: Google failed on them.
                if (response.code == 400 || response.code == 502) throw LinesRejected(response.code)
                if (response.code == 429 || response.code == 503) {
                    throw ProviderBusy(response.header("Retry-After")?.toLongOrNull()?.times(1_000L))
                }
                if (!response.isSuccessful) throw IOException("Translation is unavailable (HTTP ${response.code}).")
                JsonParser.parseString(response.body.string()).asJsonObject
            }
        val lines = response.getAsJsonArray("lines") ?: throw IOException("Translation returned an invalid response.")
        return TranslatorResponse(lines.map { value ->
            if (!value.isJsonObject) null else value.asJsonObject.let { line ->
                TranslationEntry(line.get("translation")?.takeUnless { it.isJsonNull }?.asString,
                    line.get("needsTranslation")?.takeUnless { it.isJsonNull }?.asBoolean == true)
            }
        }, response.get("detectedLang")?.takeUnless { it.isJsonNull }?.asString)
    }
}

class DeepLTranslator(private val client: OkHttpClient, private val key: String) : Translator {
    override val provider = TranslationProvider.DeepL
    override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
        if (!DeepLKey.valid(key)) throw TranslationFailure("Add your DeepL API key in Translation settings.")
        val response = client.newCall(deepLRequest(request, key))
            .awaitResponse().use { response ->
                if (!response.isSuccessful) throw IOException("DeepL couldn't translate these lyrics (HTTP ${response.code}).")
                JsonParser.parseString(response.body.string()).asJsonObject
            }
        val translation = response.getAsJsonArray("translations")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("DeepL returned an invalid response.")
        val language = translation.get("detected_source_language")?.takeUnless { it.isJsonNull }?.asString
        val translated = languageCode(language) != languageCode(request.target)
        val texts = deepLLines(translation.get("text")?.takeUnless { it.isJsonNull }?.asString.orEmpty(), request.lines.size)
        return TranslatorResponse(texts.mapIndexed { index, text ->
            text?.let { TranslationEntry(it, translated && it != request.lines[index]) }
        }, language)
    }
}

/**
 * The whole song as one document, each line in its own numbered tag: DeepL reads the lines with
 * each other for context, and every translated line comes back in its tag, so none can run into
 * another.
 */
internal fun deepLDocument(lines: List<String>): String = lines.withIndex().joinToString("\n") { (index, line) ->
    "<l i=\"$index\">${line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</l>"
}

/** [count] lines read back from [deepLDocument]'s tags; a tag that didn't come back is null. */
internal fun deepLLines(document: String, count: Int): List<String?> {
    val found = Regex("""<l i="(\d+)">(.*?)</l>""", RegexOption.DOT_MATCHES_ALL).findAll(document)
        .associate { it.groupValues[1].toInt() to it.groupValues[2] }
    return List(count) { index ->
        found[index]?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")
            ?.replace("&amp;", "&")?.trim()?.takeIf(String::isNotBlank)
    }
}

internal fun deepLRequest(request: TranslatorRequest, key: String): Request {
    fun code(language: String): String = if (languageCode(language) == "no") "NB" else language.uppercase(java.util.Locale.ROOT)
    val body = FormBody.Builder().apply {
        add("text", deepLDocument(request.lines))
        add("target_lang", code(request.target))
        request.source?.let { add("source_lang", code(it)) }
        add("tag_handling", "xml")
        add("split_sentences", "nonewlines")
        add("preserve_formatting", "1")
    }.build()
    if (body.contentLength() > 128 * 1024) throw TranslationFailure("This song exceeds DeepL's request size limit.")
    return Request.Builder().url("${DeepLKey.host(key)}/v2/translate")
        .header("Authorization", "DeepL-Auth-Key ${key.trim()}").post(body).build()
}
