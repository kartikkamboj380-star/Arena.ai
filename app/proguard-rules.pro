# The app has no JavaScript bridge and no reflective models, so there is nothing to keep for
# JavaScript or for a JSON serializer. What does have to survive shrinking are the two WebView
# callbacks, which native code calls by override, and nothing else.
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }

# Chrome Custom Tabs are entered through the browser's own service; the framework resolves them, so
# no keep rules are needed beyond the androidx.browser consumer rules.
-dontwarn androidx.browser.customtabs.**

# Never re-add a rule that keeps a class for reflection: this app must have no reflection at all.
