# Proguard rules for JAVIS
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
