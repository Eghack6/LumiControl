# ProGuard rules for LumiControl

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.lumicontrol.app.AppSettings { *; }

# Keep app entry points
-keep class com.lumicontrol.app.** { *; }
