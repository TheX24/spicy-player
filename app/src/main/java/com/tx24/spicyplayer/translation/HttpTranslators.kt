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
        val lines = response.getAsJsonArray("translations") ?: throw IOException("DeepL returned an invalid response.")
        return TranslatorResponse(lines.mapIndexed { index, value ->
            if (!value.isJsonObject) null else value.asJsonObject.let { line ->
                val text = line.get("text")?.takeUnless { it.isJsonNull }?.asString
                val language = line.get("detected_source_language")?.takeUnless { it.isJsonNull }?.asString
                TranslationEntry(text, languageCode(language) != languageCode(request.target) && text != request.lines.getOrNull(index))
            }
        })
    }
}

internal fun deepLRequest(request: TranslatorRequest, key: String): Request {
    fun code(language: String): String = if (languageCode(language) == "no") "NB" else language.uppercase(java.util.Locale.ROOT)
    val body = FormBody.Builder().apply {
        request.lines.forEach { add("text", it) }
        add("target_lang", code(request.target))
        request.source?.let { add("source_lang", code(it)) }
        add("context", request.context)
        add("preserve_formatting", "1")
        add("split_sentences", "0")
    }.build()
    if (body.contentLength() > 128 * 1024) throw TranslationFailure("This song exceeds DeepL's request size limit.")
    return Request.Builder().url("${DeepLKey.host(key)}/v2/translate")
        .header("Authorization", "DeepL-Auth-Key ${key.trim()}").post(body).build()
}
