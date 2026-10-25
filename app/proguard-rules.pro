# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.kyotobyte.cdmplus.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.kyotobyte.cdmplus.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.kyotobyte.cdmplus.**$$serializer { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# libtorrent4j: SWIG/JNI classes are looked up by name from native code
-keep class org.libtorrent4j.** { *; }
-keepclassmembers class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# NewPipeExtractor + Rhino (JS player deobfuscation) load classes by name
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
-keep class com.journeyapps.barcodescanner.** { *; }

# Build 5: keep the whole YouTube/IG/TikTok extraction stack un-shrunk (reflection, JSON, protobuf-lite, Rhino).
-keep class org.mozilla.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class org.jsoup.** { *; }
-keep class io.github.kyotobyte.cdmplus.grab.** { *; }
-keepattributes Signature, EnclosingMethod, Exceptions, *Annotation*
-dontwarn jdk.dynalink.**
-dontwarn javax.lang.model.**
-dontwarn org.mozilla.javascript.engine.**
-dontwarn com.google.protobuf.**
