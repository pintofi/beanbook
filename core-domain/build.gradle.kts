plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core-domain is pure Kotlin and depends on nothing Android-specific. It holds the
// release-blocking invariants (SPEC.md 5) so they can be unit-tested without a device.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

kotlin { jvmToolchain(21) }

tasks.withType<Test>().configureEach { useJUnit() }
