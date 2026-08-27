plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "dev.beancount.engine.local"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        ndk {
            // arm64-v8a + x86_64 only (docs/architecture.md). Every wheel we build is
            // produced for exactly these two.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(21) }
}

chaquopy {
    defaultConfig {
        version = libs.versions.python.get()

        // The host needs a CPython matching the embedded runtime's minor version for pip
        // to run (Phase 0 finding). Overridable so CI and other machines can point at
        // their own interpreter.
        val hostPython = providers.gradleProperty("beancount.buildPython")
            .getOrElse("${System.getProperty("user.home")}/tools/py313/bin/python3.13")
        buildPython(hostPython)

        pip {
            // beancount and watchfiles have no Android wheels upstream; we cross-build
            // them with cibuildwheel (docs/feasibility/s2-beancount-wheel.md,
            // s3-fava-closure.md) and resolve them from this directory.
            val wheelDir = providers.gradleProperty("beancount.wheelDir")
                .getOrElse("${System.getProperty("user.home")}/tools/wheels")
            options("--find-links", wheelDir)

            // Every version, not just the two direct ones. Naming only fava and beancount
            // left every transitive dependency free to move: two clean builds of the same
            // commit resolved click 8.4.2 and click 8.5.0, which is a supply-chain problem
            // before it is a reproducibility one — a build could pick up a version nobody
            // reviewed (docs/milestones/m14-part5.md).
            //
            // Regenerate with tools/lock-python.py after a deliberate upgrade; CLAUDE.md
            // makes upgrades their own milestone with the golden corpus re-run.
            install("-r", "requirements.lock")

            // The two cross-built wheels are pinned separately, in a generated file, because
            // their bytes differ on every build and a hash recorded on one machine cannot
            // match a wheel built on another (docs/milestones/m14-part9.md). Written by
            // tools/build-wheels.sh; without it there are no wheels to install anyway.
            install("-r", "requirements-local.lock")
        }
    }
}

dependencies {
    api(project(":core-engine"))
    api(project(":core-data"))
    implementation(libs.kotlinx.coroutines.android)
}

// The generated pin file is checked when the Python assets are actually built, and not a
// moment earlier. Checking it at configuration time made *every* Gradle invocation in a
// fresh clone fail — `:core-domain:test` has nothing to do with Python and should not care
// that a wheel has not been built (docs/milestones/m14-part10.md).
tasks.matching { it.name.matches(Regex("install.*PythonRequirements")) }.configureEach {
    val wheelDir = providers.gradleProperty("beancount.wheelDir")
        .getOrElse("${System.getProperty("user.home")}/tools/wheels")
    val localPins = layout.projectDirectory.file("requirements-local.lock").asFile
    doFirst {
        check(localPins.isFile) {
            "engine-local/requirements-local.lock is missing. It pins the two wheels this " +
                "project cross-builds, and is written beside them: " +
                "tools/build-wheels.sh --out $wheelDir"
        }
    }
}
