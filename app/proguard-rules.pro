# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ---------------------------------------------------------------------------
# Release configuration (R8 enabled from versionCode 6 onward).
# ---------------------------------------------------------------------------

# Preserve line numbers so Play Console renders fully-resolved stack traces from
# the mapping file AGP embeds in the bundle, while still obfuscating the original
# source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# TrajectoryDurationUnit round-trips through DataStore *by name*:
# AppSettingsRepository writes `.name` and reads back by matching `it.name`.
# R8 normally preserves Enum.name() because the string is baked into the enum
# constructor call, but this pins it explicitly - the failure mode is silent
# (saved trajectory units quietly reverting to defaults after an update).
-keepclassmembers enum com.example.eps_sgtracker.data.TrajectoryDurationUnit {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
