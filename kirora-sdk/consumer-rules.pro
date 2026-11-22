# ProGuard and R8 consumer rules for Kirora Android SDK

# Keep public API classes and methods
-keep public class com.kirora.androidsdk.Kirora {
    public *;
}
-keep public class com.kirora.androidsdk.model.** {
    public *;
}

# Keep Gson serialization models and annotations
# Scoped to our package so the rule does not reach into the host app's and other libraries' models.
-keepclassmembers class com.kirora.androidsdk.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.kirora.androidsdk.net.models.** { *; }

# Keep Room database entities and DAOs. Our package only: Room ships its own rules, and a
# global keep would stop R8 shrinking the app's own databases.
-keep class com.kirora.androidsdk.db.** { *; }

# Keep Retrofit API interfaces
-keep interface com.kirora.androidsdk.net.KiroraApiService { *; }

# Found by name when push = true (android-sdk-push artifact).
-keep interface com.kirora.androidsdk.push.KiroraPushBridge { *; }
