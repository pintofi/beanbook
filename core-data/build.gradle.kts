plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Workspace layout, project creation and (from M4) the journalled write protocol.
// Pure Kotlin: it takes a root directory rather than a Context, so every path rule is
// testable on the JVM.
dependencies {
    api(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin { jvmToolchain(21) }

tasks.withType<Test>().configureEach { useJUnit() }
