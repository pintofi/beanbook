plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.chaquopy) apply false
    alias(libs.plugins.detekt)
}

// Kotlin static analysis across every module, from one config. Blanket suppressions are not
// used, so the config below turns rules off by name with a reason rather
// than silencing findings at the call site.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
        basePath = rootProject.projectDir.absolutePath
    }
}
