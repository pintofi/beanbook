plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core-domain is pure Kotlin and depends on nothing Android-specific. It holds the
// release-blocking invariants (README, "How a save is written") so they can be unit-tested
// without a device.
dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

kotlin { jvmToolchain(21) }

tasks.withType<Test>().configureEach { useJUnit() }
