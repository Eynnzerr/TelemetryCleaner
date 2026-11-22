# app_process launches the uninstall helper by class name, and Shizuku's process factory is found
# reflectively because Shizuku 13 made newProcess private. Keep both names in optimized releases.
-keep class com.eynnzerr.telemetrycleaner.shell.PackageUninstaller {
    public static void main(java.lang.String[]);
}
-keep class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# Navigation 3 NavKeySerializer resolves @Serializable route classes reflectively by name.
-keepnames class * implements androidx.navigation3.runtime.NavKey
-keepclassmembers class * implements androidx.navigation3.runtime.NavKey {
    public static *** serializer();
    public *** INSTANCE;
}
