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

# ---------------------------------------------------------------------------
# Apache Commons Logging - reached via predict4java's PassPredictor.
#
# THIS IS LOAD-BEARING. Removing it reproduces the 1.5 crash: the app would not
# open at all, and a fresh install crashed the moment a ground station produced
# a pass.
#
# PassPredictor.java:68 initialises a logger in a STATIC field initialiser:
#     private static Log log = LogFactory.getLog(PassPredictor.class);
# LogFactory resolves its implementation with Class.forName on a hardcoded
# string, so nothing references LogFactoryImpl by symbol and R8's shrinker
# deletes it as unreachable (confirmed in R8's own usage.txt, which listed
# LogFactoryImpl, Jdk14Logger, SimpleLog and NoOpLog as removed). The
# Class.forName then fails inside <clinit> -> ExceptionInInitializerError, and
# because a failed static initialiser marks the class permanently erroneous,
# every later access throws NoClassDefFoundError. That is why the app could
# never be reopened.
#
# Affects all three PassPredictor call sites: recomputePasses (TRACK and
# startup), recomputeForecast (PLAN) and getSkyTrack (the sky plot).
-keep class org.apache.commons.logging.** { *; }
-dontwarn org.apache.commons.logging.**

# Keeping the package above retains Log4JLogger, AvalonLogger and
# ServletContextCleaner, which reference libraries that are not on an Android
# classpath. R8 full mode treats missing classes as build errors, so these
# suppress that. They are never loaded at runtime: LogFactoryImpl probes for
# each backend by Class.forName and falls through to Jdk14Logger, which the
# platform does provide.
-dontwarn org.apache.log4j.**
-dontwarn org.apache.avalon.framework.logger.**
-dontwarn javax.servlet.**
