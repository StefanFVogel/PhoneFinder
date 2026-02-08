# TraceBack ProGuard Rules

# Keep Google API classes
-keep class com.google.api.** { *; }
-keep class com.google.android.gms.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep model classes for JSON
-keep class com.traceback.kml.** { *; }
