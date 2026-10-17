pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "beancount-android"

// Only modules with real content exist: an empty module is a placeholder, and placeholders
// are not wanted.
include(":core-domain")     // pure Kotlin, holds the data invariants
include(":core-data")      // workspace layout and project files
include(":core-engine")     // EngineClient boundary + flavor policy types
include(":engine-local")    // Chaquopy + Beancount/Fava, loopback server
include(":engine-remote")   // hardened HTTPS client for a self-hosted Fava
include(":scanner")         // OpenCV document detection, page pipeline
include(":app")             // Compose shell, play/expert flavors
