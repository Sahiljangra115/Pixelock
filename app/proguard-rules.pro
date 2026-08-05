# Settings are stored as JSON through Gson, which reads these classes by reflection.
-keep class dev.pixelock.app.data.models.** { *; }
-keep class dev.pixelock.app.domain.** { *; }

# Logging stays out of release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
