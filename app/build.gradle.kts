import java.util.TreeMap
import java.security.MessageDigest
import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Stage the golden corpus so it lands at assets/corpus/... in the test APK.
val copyCorpusToAssets = tasks.register<Copy>("copyCorpusToAssets") {
    from(rootProject.layout.projectDirectory.dir("corpus"))
    into(layout.buildDirectory.dir("generated/corpusAssets/corpus"))
}

// The curated plugin catalogue, into the app's own assets so the Plugins screen can list
// it with no network. One file, copied rather than duplicated, so catalog/plugins.json stays the
// single source that tools/plugin-compat/catalogue.py maintains.
val copyCatalogueToAssets = tasks.register<Copy>("copyCatalogueToAssets") {
    from(rootProject.layout.projectDirectory.file("catalog/plugins.json"))
    into(layout.buildDirectory.dir("generated/catalogueAssets/catalog"))
}
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.contains("Lint", ignoreCase = true)
}.configureEach {
    dependsOn(copyCatalogueToAssets)
}

// AGP's SourceSet API rejects task Providers, so the dependency is declared explicitly:
// every androidTest asset merge must wait for the corpus to be staged.
//
// Lint's analysis tasks read the same staged directory, and until they said so Gradle only
// complained when something scheduled them alongside the merge — running lint and the
// instrumentation suite in one invocation was enough. CI runs them in separate jobs and
// never saw it, which is exactly why it was worth fixing rather than avoiding.
// Every lint task in the family, not a list of the ones seen failing: the analysis tasks, the
// vital ones, and the model generators all read the staged directory, and naming them
// individually meant fixing this three times.
tasks.matching {
    it.name.contains("AndroidTestAssets") || it.name.contains("Lint", ignoreCase = true)
}.configureEach {
    dependsOn(copyCorpusToAssets)
}

android {
    // AGP adds an encrypted "dependency metadata" block to the APK signing block, for Google
    // Play. It is opaque to anyone else, F-Droid's scanner rejects it, and nothing here uses it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Connected tests install with every runtime permission granted. The app asks for
    // notifications on first launch (Android 13+), and that dialog sits over the first
    // activity of a fresh install, so the first Compose test found "no compose hierarchies".
    // The prompt itself is exercised by hand; a test run is not the place for it.
    // Android's default asset filter drops every directory whose name starts with an underscore
    // ("<dir>_*"). The fava-portfolio-returns overlay lives under `_vendor`, so its SciPy-free
    // returns.py was silently left out of the APK while REMOVES.txt next to it was kept: the
    // plugin installed without SciPy, imported it anyway, and "did not load" for want of a
    // package that no Android build exists for. The same default minus that one rule.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    testOptions {
        installation {
            installOptions += "-g"
        }
    }
    // The Kotlin package tree is still `io.github.pintofi.beanbook.*` while `applicationId` is not. That
    // split is deliberate, not drift: the application id is the identity Play records and can
    // never be changed after the first publish, so it moved as soon as the organisation name
    // was settled. The namespace is internal, changeable at any time, and moving it touches
    // every source file in the repository, so it is its own change.
    namespace = "io.github.pintofi.beanbook.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.pintofi.beanbook"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // The release number. Each APK adds a one-digit ABI suffix to it (see androidComponents
        // below), so 4 produces 40 (universal), 41 (arm64-v8a) and 42 (x86_64). Raise this by one for every release, and
        // versionName with it.
        versionCode = 4
        versionName = "1.0.3"
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
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // An App Bundle is split per ABI by Play itself, and Android Gradle Plugin refuses to bundle
    // while per-ABI APK splits are on ("Multiple shrunk-resources files found"): the
    // splits are off for a bundle build and on for every APK build.
    val bundling = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

    splits {
        abi {
            isEnable = !bundling
            reset()
            include("arm64-v8a", "x86_64")
            // Per-ABI downloads save ~11 MB over the universal APK
            //. Universal is built for convenience only.
            isUniversalApk = true
        }
    }

    /**
     * Release signing.
     *
     * The keystore is never in this repository — `*.jks`, `*.keystore` and
     * `keystore.properties` are all ignored, and CI supplies the same four values as
     * environment variables. A build without them still succeeds and produces an **unsigned**
     * APK, because a contributor who cannot sign should still be able to build; what must not
     * happen is a build that quietly produces something signed with the wrong key, or a
     * pipeline that fails obscurely three minutes in because a secret was missing.
     *
     * `signingReport` will show which of the two happened.
     */
    val signingProperties: Properties? =
        rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
            Properties().also { properties ->
                file.inputStream().use { properties.load(it) }
            }
        }

    fun secret(name: String, property: String): String? =
        signingProperties?.getProperty(property) ?: System.getenv(name)

    val releaseStore = secret("BCA_KEYSTORE", "storeFile")
    val releaseStorePassword = secret("BCA_KEYSTORE_PASSWORD", "storePassword")
    val releaseKeyAlias = secret("BCA_KEY_ALIAS", "keyAlias")
    val releaseKeyPassword = secret("BCA_KEY_PASSWORD", "keyPassword")
    val canSign = listOf(
        releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
    ).all { !it.isNullOrBlank() } && file(releaseStore!!).exists()

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // AGP picks the schemes from minSdk, and at 28 that is v3 alone — v1 (JAR
                // signing) is only needed below API 24 and v2 is superseded by v3 from API 28.
                // Asking for all three and checking afterwards showed `apksigner verify`
                // reporting v3 true and the rest false, which is correct, not a problem.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 is on. It owns the single largest saving available (26.7 MB of dex before
            // shrinking) and was left off for a long time because turning it on against
            // Chaquopy is exactly the kind of change that fails at runtime rather than at
            // build time — see proguard-rules.pro for what has to be kept and why.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (canSign) signingConfigs.getByName("release") else null
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
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
            assets.srcDir(layout.buildDirectory.dir("generated/catalogueAssets").get().asFile)
        }
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

// Each ABI APK needs its own versionCode: F-Droid and Android treat the files as separate
// builds, and a phone must be offered the one that matches it. The code is the release number
// times ten plus a suffix, so a later release always sorts above an earlier one whatever the
// ABI. x86_64 gets the higher suffix: a device that runs both (an emulator, a Chromebook with
// ARM translation) installs the highest code it can run, and that has to be the native build,
// not the translated one. The universal APK, and the bundle Play splits itself, take the plain release number
// times ten.
val abiVersionSuffix = mapOf("arm64-v8a" to 1, "x86_64" to 2)

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            val release = output.versionCode.orNull ?: error("versionCode is not set")
            output.versionCode.set(release * 10 + (abiVersionSuffix[abi] ?: 0))
        }
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-engine"))
    implementation(project(":engine-local"))
    implementation(project(":engine-remote"))
    implementation(project(":scanner"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.telephoto.zoomable)
    implementation(libs.reorderable)
    implementation(libs.androidx.work.runtime)
    implementation(libs.argon2kt)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

apply(from = "$rootDir/gradle/play-boundaries.gradle.kts")

/**
 * Prints the SHA-256 of every release APK.
 *
 * The expert APKs are installed from a file, with no store vouching for them, so the release
 * publishes these values beside the artifacts and a user can check what they downloaded.
 *
 * Nothing is signed by this task. The release workflow signs the APKs with the project key,
 * which is held as repository secrets (never in the repository); this task only reports what
 * was built.
 */
tasks.register("releaseChecksums") {
    group = "publishing"
    description = "SHA-256 of each release APK, to publish beside the files."

    val outputsDir = layout.buildDirectory.dir("outputs/apk")
    doLast {
        val apks = outputsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "apk" && "release" in it.name }
            .sortedBy { it.name }
            .toList()

        if (apks.isEmpty()) {
            throw GradleException(
                "no release APKs found — run assembleExpertRelease or assemblePlayRelease first",
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        apks.forEach { apk ->
            digest.reset()
            apk.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            val signed = if ("unsigned" in apk.name) " (UNSIGNED)" else ""
            logger.lifecycle("$hex  ${apk.name}$signed")
        }
    }
}

/**
 * Chaquopy writes `assets/chaquopy/build.json`, a map from file name to hash, in whatever order
 * the build machine's filesystem lists the files. The content is the same on every machine and
 * the bytes are not, which makes the APK impossible to reproduce on a second machine (F-Droid
 * builds ours and compares). Rewrite the file with its keys sorted, after the assets are merged
 * and before they are packaged. The content is unchanged; only the order is fixed.
 */
fun sortedJsonKeys(value: Any?): Any? = when (value) {
    is Map<*, *> -> TreeMap<String, Any?>().also { sorted ->
        value.forEach { (key, inner) -> sorted[key as String] = sortedJsonKeys(inner) }
    }
    is List<*> -> value.map { sortedJsonKeys(it) }
    else -> value
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    doLast {
        fileTree(layout.buildDirectory.dir("intermediates/assets"))
            .matching { include("**/chaquopy/build.json") }
            .forEach { file ->
                val parsed = groovy.json.JsonSlurper().parse(file)
                val text = groovy.json.JsonOutput.prettyPrint(
                    groovy.json.JsonOutput.toJson(sortedJsonKeys(parsed)),
                )
                file.writeText(text + "\n")
            }
    }
}
