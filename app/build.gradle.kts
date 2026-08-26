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
            // The values themselves live in the per-flavor PluginPolicyProvider and only
            // there — a second copy in BuildConfig was read by nothing and could drift.
        }
        create("expert") {
            dimension = "edition"
            applicationIdSuffix = ".expert"
            versionNameSuffix = "-expert"
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
            // R8 is on. It owns the single largest saving available (26.7 MB of dex before
            // shrinking) and was left off through M1-M13 because turning it on against
            // Chaquopy is exactly the kind of change that fails at runtime rather than at
            // build time — see proguard-rules.pro for what has to be kept and why.
            isMinifyEnabled = true
            isShrinkResources = true
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
        // Flavor-specific instrumentation. Tests for the expert edition's self-update cannot
        // live in the shared androidTest source set: the classes they exercise do not exist
        // in the play build, by policy, so the shared set would not compile for it.
        getByName("androidTestExpert") { kotlin.srcDir("src/androidTestExpert/kotlin") }
        getByName("androidTestPlay") { kotlin.srcDir("src/androidTestPlay/kotlin") }

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
    implementation(project(":core-entitlement"))
    implementation(project(":engine-local"))
    implementation(project(":engine-remote"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.androidx.work.runtime)
    implementation(libs.argon2kt)
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
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

apply(from = "$rootDir/gradle/play-boundaries.gradle.kts")
