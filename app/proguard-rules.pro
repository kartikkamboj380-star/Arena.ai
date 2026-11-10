# Session/chat models are (de)serialised reflectively by kotlinx.serialization, so their
# generated serializers and property names must survive shrinking.
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn kotlinx.serialization.**
-keepclassmembers class com.kartikkamboj.arena.data.model.** { *; }
-keepclasseswithmembers class com.kartikkamboj.arena.data.model.** {
    *** Companion(...);
}

# The JS<->native bridge object is looked up by name from JavaScript.
-keepclassmembers class com.kartikkamboj.arena.web.** {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView callbacks overridden by the app live in android.webkit; keep our subclasses' overrides.
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }
