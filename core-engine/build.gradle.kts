plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The boundary between the Compose shell and whichever engine is in use (local embedded
// or remote Fava). Pure Kotlin so both can be tested without a device.
dependencies {
    api(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

kotlin { jvmToolchain(21) }

tasks.withType<Test>().configureEach { useJUnit() }
