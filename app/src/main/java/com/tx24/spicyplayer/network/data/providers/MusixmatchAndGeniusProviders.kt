package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton class MusixmatchLyricsProvider @Inject constructor(private val client:OkHttpClient,private val gson:Gson):RemoteLyricsProvider{
 override val descriptor=LyricsSourceDescriptor("musixmatch","Musixmatch",110,setOf(LyricsCapability.WORD_SYNC,LyricsCapability.LINE_SYNC,LyricsCapability.PLAIN_TEXT),upstreamFamily="musixmatch",releaseChannel=SourceReleaseChannel.EXTENDED,defaultEnabled=false)
 @Volatile private var token:String?=null
 override suspend fun fetch(r:LyricsLookupRequest):ProviderResult { return try{val t=token?:fetchToken().also{token=it}?:return ProviderResult.Unavailable(ProviderFailureCategory.AUTHENTICATION)
  val url="$BASE/macro.subtitles.get".toHttpUrl().newBuilder().addQueryParameter("format","json").addQueryParameter("namespace","lyrics_richsynced").addQueryParameter("optional_calls","track.richsync,track.lyrics").addQueryParameter("subtitle_format","lrc").addQueryParameter("app_id",APP).addQueryParameter("usertoken",t).addQueryParameter("q_artist",r.artist).addQueryParameter("q_track",r.title).addQueryParameter("f_subtitle_length",r.durationSeconds.toString()).addQueryParameter("f_subtitle_length_max_deviation","4").build()
  val root=get(url);val message=root.getAsJsonObject("message")?:return ProviderResult.Miss;if(message.getAsJsonObject("header")?.get("status_code")?.asInt!=200)return ProviderResult.Miss
  val calls=message.getAsJsonObject("body")?.getAsJsonObject("macro_calls")?:return ProviderResult.Miss
  val track=calls.path("matcher.track.get","message","body","track");if(track!=null){val title=track.get("track_name")?.asString.orEmpty();val artist=track.get("artist_name")?.asString.orEmpty();if(SpotifyTrackMatcher.normalize(title)!=SpotifyTrackMatcher.normalize(r.title)||!SpotifyTrackMatcher.normalize(artist).contains(SpotifyTrackMatcher.normalize(r.artist)))return ProviderResult.Miss}
  val rich=calls.path("track.richsync.get","message","body","richsync")?.get("richsync_body")?.asString
  if(!rich.isNullOrBlank()&&!poisoned(rich))RichSyncToTtml.convert(gson.fromJson(rich,JsonArray::class.java))?.let{return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics=it))}
  val subs=calls.path("track.subtitles.get","message","body")?.getAsJsonArray("subtitle_list");val lrc=subs?.firstOrNull()?.asJsonObject?.getAsJsonObject("subtitle")?.get("subtitle_body")?.asString
  if(!lrc.isNullOrBlank()&&!poisoned(lrc))return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics=lrc))
  val plain=calls.path("track.lyrics.get","message","body","lyrics")?.get("lyrics_body")?.asString;if(!plain.isNullOrBlank()&&!poisoned(plain))ProviderResult.Hit(RemoteLyricsPayload(plainLyrics=plain))else ProviderResult.Miss
 }catch(c:CancellationException){throw c}catch(e:IOException){ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,e.message,true)}catch(e:Exception){ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,e.message)} }
 private suspend fun fetchToken():String?=get("$BASE/token.get".toHttpUrl().newBuilder().addQueryParameter("app_id",APP).addQueryParameter("format","json").build()).path("message","body")?.get("user_token")?.asString?.takeUnless{it.all{c->c=='0'}}?:STATIC
 private suspend fun get(url:okhttp3.HttpUrl)=client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use{r->if(!r.isSuccessful)throw IOException("Musixmatch HTTP ${r.code}");gson.fromJson(r.body?.string(),JsonObject::class.java)}
 private fun poisoned(s:String)=listOf("wob gopini den","tefe woxica fero","gogoh vudob wiya","keric sohu peduf").any{s.contains(it,true)}
 private fun JsonObject.path(vararg p:String):JsonObject?{var c=this;for(k in p){c=c.getAsJsonObject(k)?:return null};return c}
 companion object{const val BASE="https://apic-desktop.musixmatch.com/ws/1.1";const val APP="web-desktop-app-v1.0";const val STATIC="21051986b9886e2d7bd5d8295b15d605c14e13e33326a3a0e50e1b"}
}
internal object RichSyncToTtml{fun convert(a:JsonArray):String?{val ps=a.mapIndexedNotNull{i,e->val l=e.asJsonObject;val s=l.get("ts")?.asDouble?:return@mapIndexedNotNull null;val end=l.get("te")?.asDouble?:s;val words=l.getAsJsonArray("l")?:return@mapIndexedNotNull null;val spans=words.mapIndexedNotNull{j,w0->val w=w0.asJsonObject;val text=w.get("c")?.asString?:return@mapIndexedNotNull null;val ws=s+(w.get("o")?.asDouble?:0.0);val we=if(j+1<words.size())s+(words[j+1].asJsonObject.get("o")?.asDouble?:0.0)else end;"<span begin=\"${sec(ws)}\" end=\"${sec(we)}\">${xml(text)}</span>"};if(spans.isEmpty())null else "<p begin=\"${sec(s)}\" end=\"${sec(end)}\" xml:id=\"L$i\">${spans.joinToString("")}</p>"};if(ps.isEmpty())return null;return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${ps.joinToString("")}</div></body></tt>"""};private fun sec(v:Double)="${"%.3f".format(Locale.ROOT,v)}s";private fun xml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")}

@Singleton class GeniusLyricsProvider @Inject constructor(private val client:OkHttpClient,private val gson:Gson):RemoteLyricsProvider{
 override val descriptor=LyricsSourceDescriptor("genius","Genius",160,setOf(LyricsCapability.PLAIN_TEXT, LyricsCapability.CONTRIBUTOR_CREDITS),releaseChannel=SourceReleaseChannel.EXPERIMENTAL,defaultEnabled=false)
 override suspend fun fetch(r:LyricsLookupRequest):ProviderResult { return try{val url="https://genius.com/api/search/multi".toHttpUrl().newBuilder().addQueryParameter("q","${r.artist} ${r.title}").build();val root=get(url);val sections=root.getAsJsonObject("response")?.getAsJsonArray("sections")?:return ProviderResult.Miss;var page:String?=null
  sections.flatMap{it.asJsonObject.getAsJsonArray("hits")?.toList().orEmpty()}.map{it.asJsonObject.getAsJsonObject("result")}.firstOrNull{res->SpotifyTrackMatcher.normalize(res.get("title")?.asString.orEmpty())==SpotifyTrackMatcher.normalize(r.title)&&SpotifyTrackMatcher.normalize(res.getAsJsonObject("primary_artist")?.get("name")?.asString.orEmpty()).contains(SpotifyTrackMatcher.normalize(r.artist))}?.let{page=it.get("url")?.asString}
  val target=page?:return ProviderResult.Miss;val html=client.newCall(Request.Builder().url(target).get().build()).awaitResponse().use{it.body?.string().orEmpty()};val parts=Regex("<div[^>]*data-lyrics-container=[\"']true[\"'][^>]*>(.*?)</div>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(html).map{clean(it.groupValues[1])}.filter{it.isNotBlank()}.toList();if(parts.isEmpty())ProviderResult.Miss else ProviderResult.Hit(RemoteLyricsPayload(plainLyrics=parts.joinToString("\n\n")))
 }catch(c:CancellationException){throw c}catch(e:ProviderHttpException){e.unavailable()}catch(e:IOException){ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,e.message,true)}catch(e:Exception){ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,e.message)} }
 private suspend fun get(url:okhttp3.HttpUrl)=client.newCall(Request.Builder().url(url).header("Referer","https://genius.com/").get().build()).awaitResponse().use{r->if(!r.isSuccessful)throw ProviderHttpException("Genius",r.code);gson.fromJson(r.body?.string(),JsonObject::class.java)}
 private fun clean(s:String)=s.replace(Regex("<br\\s*/?>",RegexOption.IGNORE_CASE),"\n").replace(Regex("<[^>]+>"),"").replace("&amp;","&").replace("&#x27;", "'").replace("&quot;","\"").trim()
}
