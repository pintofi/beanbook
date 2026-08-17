plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Stage the golden corpus so it lands at assets/corpus/... in the test APK.
val copyCorpusToAssets = tasks.register<Copy>("copyCorpusToAssets") {
    from(rootProject.layout.projectDirectory.dir("corpus"))
    into(layout.buildDirectory.dir("generated/corpusAssets/corpus"))
}

// AGP's SourceSet API rejects task Providers, so the dependency is declared explicitly:
// every androidTest asset merge must wait for the corpus to be staged.
tasks.matching { it.name.contains("AndroidTestAssets") }.configureEach {
    dependsOn(copyCorpusToAssets)
}

android {
    namespace = "dev.beancount.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.beancount.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("play") {
            dimension = "edition"
            // Feature parity with expert except where Play policy forbids it; the three
            // differences are enforced by :verifyPlayFlavorBoundaries, not by convention.
            buildConfigField("boolean", "ALLOWS_NATIVE_PLUGIN_CODE", "false")
            buildConfigField("boolean", "ALLOWS_ANDROID_BRIDGE", "false")
            buildConfigField("boolean", "ALLOWS_SELF_UPDATE", "false")
        }
        create("expert") {
            dimension = "edition"
            applicationIdSuffix = ".expert"
            versionNameSuffix = "-expert"
            buildConfigField("boolean", "ALLOWS_NATIVE_PLUGIN_CODE", "true")
            buildConfigField("boolean", "ALLOWS_ANDROID_BRIDGE", "true")
            buildConfigField("boolean", "ALLOWS_SELF_UPDATE", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            // Per-ABI downloads save ~11 MB over the universal APK
            // (docs/feasibility/s10-size.md). Universal is built for convenience only.
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            // R8 against Chaquopy is untested (Phase 0 risk R6) and owns ~22 MB of
            // savings. Turning it on is its own task with evidence, not a default flip.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(21) }

    sourceSets {
        getByName("main") { kotlin.srcDir("src/main/kotlin") }
        getByName("play") { kotlin.srcDir("src/play/kotlin") }
        getByName("expert") { kotlin.srcDir("src/expert/kotlin") }
        getByName("androidTest") {
            kotlin.srcDir("src/androidTest/kotlin")
            // The golden corpus is shipped inside the test APK rather than duplicated:
            // the same files the desktop differential runner uses (tools/corpus). The
            // copy keeps the "corpus/" prefix, which srcDir on the directory itself
            // would flatten away.
            assets.srcDir(layout.buildDirectory.dir("generated/corpusAssets").get().asFile)
        }
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-engine"))
    implementation(project(":engine-local"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}

apply(from = "$rootDir/gradle/play-boundaries.gradle.kts")
