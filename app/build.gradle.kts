plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.ksp) // Added KSP for Room code generation
}

android {
    namespace = "com.example.eps_sgtracker"
    compileSdk = 37 // Simplified assignment syntax

    defaultConfig {
        applicationId = "com.mdeutsch.spt"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ships native symbol tables in BUNDLE-METADATA so Play can symbolicate crashes in
            // the .so files pulled in transitively by compose-ui-graphics and datastore. Play
            // strips these before delivery, so FULL costs nothing in install size.
            //
            // REQUIRES AN INSTALLED NDK. Extraction runs objcopy from the NDK, and with none
            // present AGP's extractReleaseNativeDebugMetadata task SUCCEEDS while silently
            // producing nothing - no debugsymbols entry reaches the bundle and Play still reports
            // "you've not uploaded debug symbols". Verify with:
            //   unzip -l app-release.aab | grep debugsymbols
            // Install via Android Studio > SDK Manager > SDK Tools > NDK (Side by side).
            // Worth knowing before spending the download: this app has no first-party native code,
            // and the AndroidX .so files above ship pre-stripped, so the extracted symbols would
            // carry little beyond exported function names. This clears the warning more than it
            // buys real debugging.
            ndk { debugSymbolLevel = "FULL" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Room writes its schema JSON here (AppDatabase has exportSchema = true). Commit app/schemas so a
// future version bump has a v1 baseline to author and test a Migration against - it cannot be
// regenerated once v1 has shipped.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Androidx & Lifecycle (FIXED: points directly to your catalog's runtime-ktx key)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // collectAsStateWithLifecycle - was already on the classpath transitively, declared explicitly
    // because the screens now use it directly.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    // Navigation & Data Storage
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Jetpack Compose BOM UI Engine
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Room Database Architecture Layer
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Third-Party Domain API Dependencies
    implementation(libs.okhttp)
    implementation(libs.predict4java)

    // Testing Foundations
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
