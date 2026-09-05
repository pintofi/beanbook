package io.github.pintofi.beanbook.engine.remote

import java.io.File
import java.nio.file.Files
import java.security.KeyStore

/**
 * A throwaway self-signed certificate for the TLS test.
 *
 * Built with the JDK's own `keytool` rather than a crypto library: this is test scaffolding,
 * it must not add a dependency to a module whose whole point is having none, and the
 * certificate is generated fresh each run so nothing long-lived exists to leak.
 */
internal object SelfSigned {

    val PASSWORD: CharArray = "test-only".toCharArray()

    fun keystore(): KeyStore {
        val directory = Files.createTempDirectory("self-signed").toFile()
        val file = File(directory, "keystore.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath

        val process = ProcessBuilder(
            keytool, "-genkeypair",
            "-alias", "self",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-dname", "CN=localhost",
            "-ext", "SAN=ip:127.0.0.1",
            "-keystore", file.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", String(PASSWORD),
            "-keypass", String(PASSWORD),
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }

        return KeyStore.getInstance("PKCS12").apply {
            file.inputStream().use { load(it, PASSWORD) }
            directory.deleteRecursively()
        }
    }
}
