// :verifyPlayFlavorBoundaries — a release gate, not a lint warning.
//
// The play and expert editions have feature parity except for three differences forced by
// Google Play policy (docs/distribution.md 2.1):
//
//   D1  play must not install packages containing native code (.so)
//   D2  play must not expose the Java/Android bridge to user plugin code
//   D3  play must not contain self-update / APK-installer code
//
// plus the monetization rules (docs/distribution.md 4.1):
//
//   M1  Play Billing belongs to the play edition and nowhere else. The expert build is not
//       distributed through Play, so shipping Play Billing in it would carry a proprietary
//       library that cannot work, into the one edition users install by hand.
//   M2  no third-party payment SDK in either edition, ever.
//
// Each check below can fail a real build. `./gradlew :app:verifyPlayFlavorBoundaries`
// must be run for every release.

val forbiddenPlayDependencies = listOf(
    "com.paypal",                          // M2: no third-party payment SDKs
    "com.stripe",
    "com.braintreepayments",
)

/**
 * M1: Play Billing may reach the expert classpath through nothing at all.
 *
 * The play edition needs it, so it cannot simply be forbidden everywhere. What must hold is
 * that it stays on the play side: `playImplementation` in app/build.gradle.kts is what puts
 * it there, and this is what notices when someone changes that to `implementation`.
 */
val forbiddenExpertDependencies = listOf(
    "com.android.billingclient",
    "com.google.android.gms:play-services-billing",
)

/** Patterns that must not appear in code compiled into the play edition. */
val forbiddenPlaySourcePatterns = listOf(
    Regex("""allowsNativeCode\s*=\s*true"""),   // D1
    Regex("""allowsAndroidBridge\s*=\s*true"""), // D2
    Regex("""allowsPackageDownload\s*=\s*true"""), // D1: no fetching code, native or not
)

/**
 * D3, widened: **no** edition may install a package.
 *
 * These three were play-only rules, because Expert shipped a self-updater and needed the API
 * and the permission. That updater is gone (B40): Android already refuses any package not
 * signed with the key that signed the installed app, which is the check that protects an
 * update, so ours only added a paste-a-code step and a sensitive permission. With no caller
 * left in either flavor, the honest rule is the stronger one — and it is what stops the
 * subsystem being rebuilt without a decision, in Expert as much as in Play.
 *
 * Scanned across `src/main`, `src/play` and `src/expert`, and their manifests.
 */
val forbiddenInstallerPatterns = listOf(
    Regex("""PackageInstaller"""),
    Regex("""ACTION_INSTALL_PACKAGE"""),
    Regex("""REQUEST_INSTALL_PACKAGES"""),
)

/**
 * Statements the play edition must actually make.
 *
 * The forbidden-pattern rules above catch a boundary flipped to `true`. They do not catch a
 * boundary that stops being stated at all — deleting `PluginPolicyProvider.kt` from the play
 * source set passed every check while removing the restriction entirely. A boundary has to be
 * asserted, not merely not-contradicted.
 */
val forbiddenSharedSourcePatterns = listOf(
    // M1: billing code lives in src/play only. In src/main it would compile into the expert
    // build too, which is the thing this rule exists to stop.
    Regex("""BillingClient"""),
)

//
// D3 is deliberately not in this list any more. It was `allowsSelfUpdate = false`, and the
// flag was deleted with the self-updater: nothing read it, and a policy object asserting a
// capability the app does not have is an assertion about nothing. D3 is now enforced by
// `forbiddenInstallerPatterns` across every edition, which is a stronger claim than a play-side
// declaration was — absence everywhere, rather than a false stated in one source set.
val requiredPlaySourcePatterns = listOf(
    Regex("""allowsNativeCode\s*=\s*false"""),    // D1
    Regex("""allowsAndroidBridge\s*=\s*false"""), // D2
    Regex("""allowsPackageDownload\s*=\s*false"""), // D1
)

/**
 * R9: GPL-2.0-only code stays in the engine process.
 *
 * The app is GPL-2.0-only because Beancount is, and AndroidX — most of the app process — is
 * Apache-2.0, which is not compatible with it. The resolution chosen is **separating the
 * processes**: Beancount, Fava and the whole Python runtime live in `:engine`, and the app
 * talks to them over a binder rather than linking them (docs/architecture.md 7.1).
 *
 * That separation is a property of the code, not of the intent, and it broke once already —
 * a debug harness called `PluginLoader`, which started an interpreter in whichever process
 * asked. So it is checked: nothing compiled into the app process may start Python.
 *
 * These patterns are matched in `src/main`, `src/play` and `src/expert` — everything that
 * ends up in the app process. `:engine-local` is deliberately not scanned; it owns both
 * sides of the boundary, and its service is the one thing that is *supposed* to start an
 * interpreter.
 */
val forbiddenAppProcessPatterns = listOf(
    Regex("""PythonRuntime"""),
    Regex("""Python\.start"""),
    Regex("""com\.chaquo\.python"""),
)

/** Permissions the play edition must never request. */
val forbiddenPlayPermissions = listOf(
    "android.permission.REQUEST_INSTALL_PACKAGES",
)

/**
 * Source with comments removed, so the boundary rules read code rather than prose.
 *
 * This matters in both directions. A file explaining that it deliberately does *not* use
 * `PackageInstaller` tripped the forbidden-pattern rule once the scan widened to the
 * dependency modules; and a required assertion could equally have been satisfied by a comment
 * claiming the policy rather than by the policy object stating it (R6).
 *
 * String literals are kept: naming a forbidden class in a string is how you would reach it
 * reflectively, which is exactly what the rule should still catch.
 */
fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var inString = false
    var inChar = false
    while (i < source.length) {
        val c = source[i]
        val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
        when {
            inString -> {
                out.append(c)
                if (c == '\\') { if (i + 1 < source.length) out.append(source[i + 1]); i++ }
                else if (c == '"') inString = false
            }
            inChar -> {
                out.append(c)
                if (c == '\\') { if (i + 1 < source.length) out.append(source[i + 1]); i++ }
                else if (c == '\'') inChar = false
            }
            c == '"' -> { inString = true; out.append(c) }
            c == '\'' -> { inChar = true; out.append(c) }
            c == '/' && next == '/' -> { while (i < source.length && source[i] != '\n') i++; continue }
            c == '/' && next == '*' -> {
                i += 2
                while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                i += 2
                continue
            }
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

val verifyPlayFlavorBoundaries = tasks.register("verifyPlayFlavorBoundaries") {
    group = "verification"
    description = "Fails if the play edition crosses a Google Play policy boundary."

    // The app's own play and shared sources, plus every module that compiles into the play
    // edition. Scanning only app/src was the gap: `PackageInstaller` in :core-data, or any
    // other forbidden call living one module down, satisfied the gate while still shipping
    // in the APK (R6).
    val playSourceDirs = listOf(
        file("src/play"),
        file("src/main"),
    ) + listOf(
        "core-domain", "core-data", "core-engine", "core-entitlement",
        "engine-local", "engine-remote",
    ).map { File(rootDir, "$it/src/main") }
    val manifestFiles = playSourceDirs.map { File(it, "AndroidManifest.xml") }
    val configurationNames = listOf(
        "playDebugRuntimeClasspath",
        "playReleaseRuntimeClasspath",
    )
    val expertConfigurationNames = listOf(
        "expertDebugRuntimeClasspath",
        "expertReleaseRuntimeClasspath",
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

    val resolvedExpertCoordinates = provider {
        expertConfigurationNames.flatMap { name ->
            val configuration = configurations.findByName(name) ?: return@flatMap emptyList()
            if (!configuration.isCanBeResolved) return@flatMap emptyList()
            runCatching {
                configuration.incoming.resolutionResult.allComponents.map { it.id.displayName }
            }.getOrElse { emptyList() }
        }
    }

    // Shared sources: everything compiled into *both* editions.
    val sharedSourceDirs = listOf(file("src/main")) + listOf(
        "core-domain", "core-data", "core-engine", "core-entitlement",
        "engine-local", "engine-remote",
    ).map { File(rootDir, "$it/src/main") }

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

        for (coordinate in resolvedExpertCoordinates.get()) {
            for (forbidden in forbiddenExpertDependencies) {
                if (coordinate.contains(forbidden)) {
                    violations += "M1: Play Billing on the expert classpath: $coordinate " +
                        "(it belongs to the play edition only)"
                }
            }
        }

        // --- source check -----------------------------------------------------------
        for (dir in playSourceDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { source ->
                    val text = stripComments(source.readText())
                    for (pattern in forbiddenPlaySourcePatterns) {
                        if (pattern.containsMatchIn(text)) {
                            violations += "forbidden pattern /${pattern.pattern}/ in " +
                                "${source.relativeTo(projectDir)} (compiled into play)"
                        }
                    }
                }
        }

        // --- shared-source check: billing must not be compiled into both editions -----
        for (dir in sharedSourceDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { source ->
                    val text = stripComments(source.readText())
                    for (pattern in forbiddenSharedSourcePatterns) {
                        if (pattern.containsMatchIn(text)) {
                            violations += "M1: /${pattern.pattern}/ in " +
                                "${source.relativeTo(rootDir)}, which compiles into both " +
                                "editions; billing code belongs in src/play"
                        }
                    }
                }
        }

        // --- required-statement check ------------------------------------------------
        val playOnlySource = file("src/play").takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            ?.joinToString("\n") { it.readText() }
            .orEmpty()

        for (pattern in requiredPlaySourcePatterns) {
            if (!pattern.containsMatchIn(playOnlySource)) {
                violations += "the play source set no longer states /${pattern.pattern}/; " +
                    "a Play boundary must be asserted, not merely absent"
            }
        }

        // --- R9 and D3: neither an interpreter nor an installer in the app process -----
        val appProcessDirs = listOf(file("src/main"), file("src/play"), file("src/expert"))
        for (dir in appProcessDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { source ->
                    val text = stripComments(source.readText())
                    for (pattern in forbiddenAppProcessPatterns) {
                        if (pattern.containsMatchIn(text)) {
                            violations += "R9: /${pattern.pattern}/ in " +
                                "${source.relativeTo(projectDir)} would start a Python " +
                                "interpreter in the app process; go through the engine binder"
                        }
                    }
                    for (pattern in forbiddenInstallerPatterns) {
                        if (pattern.containsMatchIn(text)) {
                            violations += "D3: /${pattern.pattern}/ in " +
                                "${source.relativeTo(projectDir)} would install a package; " +
                                "no edition does that any more (B40)"
                        }
                    }
                }
        }

        // Both editions' manifests, not only play's: the permission is forbidden everywhere.
        for (manifest in appProcessDirs.map { File(it, "AndroidManifest.xml") }) {
            if (!manifest.exists()) continue
            val text = manifest.readText()
            for (pattern in forbiddenInstallerPatterns) {
                if (pattern.containsMatchIn(text)) {
                    violations += "D3: /${pattern.pattern}/ in " +
                        "${manifest.relativeTo(projectDir)}"
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
                "${forbiddenPlaySourcePatterns.size} forbidden + " +
                "${requiredPlaySourcePatterns.size} required + " +
                "${forbiddenAppProcessPatterns.size} process-separation + " +
                "${forbiddenInstallerPatterns.size} installer source rules, " +
                "${forbiddenPlayPermissions.size} permission rules)"
        )
    }
}

// The gate is not optional: assembling a play release runs it.
/**
 * The permission rules again, against the manifest that actually ships.
 *
 * `verifyPlayFlavorBoundaries` reads `src/play` and `src/main` and runs before merging, so a
 * permission contributed by a *library* manifest — which is how four of the seven in this app
 * arrive — was invisible to it. This task reads the merged output instead, which is the only
 * place the question "what does the shipped APK ask for" can honestly be answered (R6).
 */
val verifyPlayMergedManifest = tasks.register("verifyPlayMergedManifest") {
    group = "verification"
    description = "Fails if a merged play manifest declares a forbidden permission."

    val mergedRoot = layout.buildDirectory.dir("intermediates/merged_manifests")
    val forbidden = forbiddenPlayPermissions

    doLast {
        val root = mergedRoot.get().asFile
        val manifests = root.walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .filter { it.path.contains("play", ignoreCase = true) }
            .toList()

        if (manifests.isEmpty()) {
            // Nothing to check is not the same as nothing wrong. Say so rather than pass.
            logger.lifecycle("verifyPlayMergedManifest: no merged play manifest yet; nothing checked")
            return@doLast
        }

        val violations = mutableListOf<String>()
        for (manifest in manifests) {
            val text = manifest.readText()
            for (permission in forbidden) {
                if (text.contains(permission)) {
                    violations += "forbidden permission $permission in the merged manifest " +
                        manifest.relativeTo(root)
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Play merged-manifest violations (${violations.size}):\n" +
                    violations.joinToString("\n") { "    - $it" }
            )
        }
        logger.lifecycle(
            "verifyPlayMergedManifest: OK (${manifests.size} merged manifest(s), " +
                "${forbidden.size} permission rules)"
        )
    }
}

tasks.matching { it.name.matches(Regex("process.*Play.*Manifest")) }.configureEach {
    finalizedBy(verifyPlayMergedManifest)
}

tasks.matching { it.name.startsWith("assemblePlay") }.configureEach {
    dependsOn(verifyPlayFlavorBoundaries)
    dependsOn(verifyPlayMergedManifest)
}
