plugins {
    alias(libs.plugins.android.application)
}

// Fork release signing: CI decodes the RELEASE_KEYSTORE_BASE64 secret to a file and passes its
// path here via env vars, so releases are signed consistently across builds. Without those env
// vars (local dev builds), `release` falls back to the debug signing config so it still installs.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val hasReleaseSigning = !releaseKeystorePath.isNullOrEmpty() && !releaseKeystorePassword.isNullOrEmpty()

android {
    namespace = "net.eneiluj.nextcloud.phonetrack"
    // compileSdk and targetSdk track the newest Android version (Android 17). Raising targetSdk
    // opts the app into that release's behavior changes: bump it deliberately and test on it.
    compileSdk = 37

    defaultConfig {
        // this fork's own ID (the original app is net.eneiluj.nextcloud.phonetrack): both can be
        // installed side by side. Never change it again: an app with another ID is another app.
        applicationId = "io.github.lazzurs.phonetrack"
        // Oldest Android version still in Google's monthly security bulletin (Android 14, see
        // RELEASING.md). Raise it when that version drops out of the bulletin.
        minSdk = 34
        targetSdk = 37
        // from gradle.properties, the single source of the version (see RELEASING.md)
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
        vectorDrawables.useSupportLibrary = true
        resValue("string", "applicationId", "io.github.lazzurs.phonetrack")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "phonetrack-fork"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink, optimize and obfuscate code, then drop unused resources.
            // The mapping file (for readable stack traces) is attached to each GitHub release.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // AGP 9 defaults this to false; defaultConfig/flavors below use resValue
        resValues = true
    }

    androidResources {
        // Generates the locale_config used by Android 13+ per-app language settings
        // from the values-* folders (default locale set in res/resources.properties).
        generateLocaleConfig = true
    }

    flavorDimensions += "default"
    productFlavors {
        create("normal") {
            dimension = "default"
            resValue("string", "app_name", "PhoneTrack")
        }
        create("dev") {
            dimension = "default"
            applicationId = "io.github.lazzurs.phonetrack.dev"
            resValue("string", "applicationId", "io.github.lazzurs.phonetrack.dev")
            resValue("string", "app_name", "PhoneTrack Dev")
        }
        create("play") {
            dimension = "default"
            applicationId = "io.github.lazzurs.phonetrack.play"
            resValue("string", "applicationId", "io.github.lazzurs.phonetrack.play")
            resValue("string", "app_name", "PhoneTrack")
        }
    }

    testOptions {
        // Robolectric needs the merged manifest and resources
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // There are no lint errors left: keep it that way. Warnings don't fail the build
        // (some, like newer library versions, appear without any code change).
        abortOnError = true
        disable += "MissingTranslation"
    }
}

// cert4android ships Java 21 class files, which JDK 17's javac can't read: compile with JDK 21.
// The bytecode target stays Java 17 (compileOptions).
tasks.withType<JavaCompile>().configureEach {
    javaCompiler = javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Robolectric needs Java 21 to emulate SDK 35+.
tasks.withType<Test>().configureEach {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
    // Robolectric's SDK 36 sandbox sets up shared memory through FileDescriptor internals
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.cardview)
    implementation(libs.material)

    implementation(libs.cert4android)
    implementation(libs.nextcloud.sso)
    implementation(libs.gson)

    implementation(libs.osmdroid.android)
    implementation(libs.osmdroid.mapsforge)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.junit)
}
