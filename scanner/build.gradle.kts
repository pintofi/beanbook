plugins {
    alias(libs.plugins.android.library)
}

/**
 * Document scanning: edge detection, perspective correction, and the page pipeline.
 *
 * **Its own module so the dependency is visible.** OpenCV is ~24.8 MB of native code for
 * arm64-v8a, and putting it here rather than in `:app` means the Gradle graph says exactly
 * what depends on it, and `:verifyPlayFlavorBoundaries` can police that. Nothing else in the
 * app links it.
 *
 * **Licence.** OpenCV 4.12.0 is Apache-2.0, which is not GPL-2.0-compatible in general — but
 * the resolution this project already uses covers it. GPL-2.0-only code (Beancount, Fava,
 * CPython) lives in the `:engine` process and nothing here touches it
 * (docs/architecture.md §7.1); the app process is where Apache-2.0 AndroidX and CameraX
 * already are, and this joins them. No new exception is needed and none is claimed.
 *
 * Bundled, not downloaded: D1 and D3 forbid the Play edition fetching executable code, and a
 * dependency compiled into the APK is neither. Both editions get the scanner.
 */
android {
    namespace = "io.github.pintofi.beanbook.scanner"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The same two this project ships everywhere else. OpenCV's AAR also carries
        // armeabi-v7a and x86; excluding them keeps ~60 MB of native code out of the build.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(21) }

    sourceSets {
        getByName("main") { kotlin.srcDir("src/main/kotlin") }
        getByName("androidTest") { kotlin.srcDir("src/androidTest/kotlin") }
    }
}

dependencies {
    // The crop is expressed in the domain's own `CropQuad`, so OpenCV's types never reach the
    // app module. That is what keeps this dependency contained: `implementation`, not `api`.
    api(project(":core-domain"))
    implementation(libs.opencv)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
