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
            install("fava==1.30.15")
            install("beancount==3.2.3")
        }
    }
}

dependencies {
    api(project(":core-engine"))
    api(project(":core-data"))
    implementation(libs.kotlinx.coroutines.android)
}
