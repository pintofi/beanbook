plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The remote Fava client. Pure Kotlin on purpose: it speaks HTTPS through the JDK's own
// stack, which Android provides too, so every rule that decides whether a connection is
// safe can be tested on the JVM — including "a bad certificate is fatal", which is asserted
// against a real TLS handshake rather than a mock (docs/threat-model.md section 3.5).
//
// There is deliberately no HTTP library here. An early design sketched OkHttp; the
// platform stack does what is needed, adds no dependency to audit or license, and keeps the
// TLS configuration as the platform's default rather than something this app can weaken.
dependencies {
    implementation(project(":core-engine"))
    implementation(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin { jvmToolchain(21) }

tasks.withType<Test>().configureEach { useJUnit() }
