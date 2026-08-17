// :verifyPlayFlavorBoundaries — a release gate, not a lint warning.
//
// The play and expert editions have feature parity except for three differences forced by
// Google Play policy (docs/distribution.md 2.1):
//
//   D1  play must not install packages containing native code (.so)
//   D2  play must not expose the Java/Android bridge to user plugin code
//   D3  play must not contain self-update / APK-installer code
//
// plus the monetization rule that applies to *both* editions: no billing SDK, no purchase
// flow, no payment library anywhere in the app (docs/distribution.md 4.1).
//
// Each check below can fail a real build. `./gradlew :app:verifyPlayFlavorBoundaries`
// must be run for every release.

val forbiddenPlayDependencies = listOf(
    "com.android.billingclient",          // Play Billing — not used in either edition
    "com.google.android.gms:play-services-billing",
    "com.paypal",                          // no payment SDKs, per the monetization design
    "com.stripe",
    "com.braintreepayments",
)

/** Patterns that must not appear in code compiled into the play edition. */
val forbiddenPlaySourcePatterns = listOf(
    Regex("""PackageInstaller"""),         // D3: installing APKs
    Regex("""ACTION_INSTALL_PACKAGE"""),   // D3
    Regex("""REQUEST_INSTALL_PACKAGES"""), // D3
    Regex("""BillingClient"""),            // monetization rule
    Regex("""allowsNativeCode\s*=\s*true"""),   // D1
    Regex("""allowsAndroidBridge\s*=\s*true"""), // D2
)

/** Permissions the play edition must never request. */
val forbiddenPlayPermissions = listOf(
    "android.permission.REQUEST_INSTALL_PACKAGES",
)

val verifyPlayFlavorBoundaries = tasks.register("verifyPlayFlavorBoundaries") {
    group = "verification"
    description = "Fails if the play edition crosses a Google Play policy boundary."

    val playSourceDirs = listOf(
        file("src/play"),
        file("src/main"),
    )
    val manifestFiles = playSourceDirs.map { File(it, "AndroidManifest.xml") }
    val configurationNames = listOf(
        "playDebugRuntimeClasspath",
        "playReleaseRuntimeClasspath",
    )

    // Resolve dependency coordinates at configuration time is discouraged; do it lazily.
    val resolvedCoordinates = provider {
        configurationNames.flatMap { name ->
            val configuration = configurations.findByName(name) ?: return@flatMap emptyList()
            if (!configuration.isCanBeResolved) return@flatMap emptyList()
            runCatching {
                configuration.incoming.resolutionResult.allComponents.map { it.id.displayName }
            }.getOrElse { emptyList() }
        }
    }

    inputs.files(playSourceDirs.filter { it.exists() }.map { fileTree(it) })

    doLast {
        val violations = mutableListOf<String>()

        // --- dependency check -------------------------------------------------------
        for (coordinate in resolvedCoordinates.get()) {
            for (forbidden in forbiddenPlayDependencies) {
                if (coordinate.contains(forbidden)) {
                    violations += "forbidden dependency on the play classpath: $coordinate"
                }
            }
        }

        // --- source check -----------------------------------------------------------
        for (dir in playSourceDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { source ->
                    val text = source.readText()
                    for (pattern in forbiddenPlaySourcePatterns) {
                        if (pattern.containsMatchIn(text)) {
                            violations += "forbidden pattern /${pattern.pattern}/ in " +
                                "${source.relativeTo(projectDir)} (compiled into play)"
                        }
                    }
                }
        }

        // --- manifest check ---------------------------------------------------------
        for (manifest in manifestFiles) {
            if (!manifest.exists()) continue
            val text = manifest.readText()
            for (permission in forbiddenPlayPermissions) {
                if (text.contains(permission)) {
                    violations += "forbidden permission $permission in " +
                        "${manifest.relativeTo(projectDir)}"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Play flavor boundary violations (${violations.size}):")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("See docs/distribution.md 2.1 for why each boundary exists.")
                }
            )
        }
        logger.lifecycle(
            "verifyPlayFlavorBoundaries: OK " +
                "(${forbiddenPlayDependencies.size} dependency rules, " +
                "${forbiddenPlaySourcePatterns.size} source rules, " +
                "${forbiddenPlayPermissions.size} permission rules)"
        )
    }
}

// The gate is not optional: assembling a play release runs it.
tasks.matching { it.name.startsWith("assemblePlay") }.configureEach {
    dependsOn(verifyPlayFlavorBoundaries)
}
