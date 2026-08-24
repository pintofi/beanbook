plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Entitlement state: the 30-day trial, contribution keys, and which features each tier
// unlocks. Pure Kotlin and free of any billing or payment dependency — there is no billing
// SDK in this project (docs/distribution.md §4.1).
dependencies {
    implementation(libs.eddsa)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

kotlin { jvmToolchain(21) }

tasks.withType<Test>().configureEach { useJUnit() }
