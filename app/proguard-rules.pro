# R8 rules for the release build. Libraries bring their own rules (OkHttp, Retrofit, Coil,
# Compose); these cover what this app does by reflection.

# Gson reads and writes these by field name: the disk cache of picked lyrics and romanizations,
# and LRCLIB's responses.
-keepclassmembers class com.tx24.spicyplayer.lyrics.NextLyricsBackend$StoredPick { <fields>; }
-keepclassmembers class com.tx24.spicyplayer.lyrics.NextLyricsBackend$StoredRoman { <fields>; }
-keepclassmembers class com.tx24.spicyplayer.network.data.RemoteLyricsPayload { <fields>; }
-keepclassmembers class com.tx24.spicyplayer.network.data.LyricsAttribution { <fields>; }
-keepclassmembers class com.tx24.spicyplayer.network.data.LyricsContributor { <fields>; }
-keep class com.tx24.spicyplayer.network.model.** { <fields>; <init>(...); }

# Retrofit builds the LRCLIB client from this interface's annotations and generic signatures.
-keep interface com.tx24.spicyplayer.network.service.LyricsService { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Kuromoji finds its dictionary next to its own classes, so their package names must stay.
-keep class com.atilika.kuromoji.** { *; }

# Crash traces stay readable with the mapping file.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
