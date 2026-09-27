# R8 rules for release builds (isMinifyEnabled in app/build.gradle.kts).
#
# Components declared in the manifest and classes referenced from layout / preference XML
# are kept automatically; AndroidX, Gson and Nextcloud SSO ship their own rules.

# Readable stack traces in crash reports and the in-app system log: keep line numbers,
# and map them back with the mapping.txt attached to each release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Libraries without consumer rules that load classes or resources by name.
# There is no instrumented test run of a minified build, so keep them whole rather
# than guess: the size cost is small next to the risk of breaking maps or TLS on device.
#   osmdroid: tile providers and modules
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
#   mapsforge: render themes (XML) and the map file reader
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**
#   cert4android: certificate trust and its dialog (security-sensitive, cheap to keep)
-keep class at.bitfire.cert4android.** { *; }
-dontwarn at.bitfire.cert4android.**

# Stored in Bundles / saved instance state (Serializable): field names must not change
# between the writer and the reader.
-keepclassmembers class net.eneiluj.nextcloud.phonetrack.model.** implements java.io.Serializable {
    <fields>;
}
