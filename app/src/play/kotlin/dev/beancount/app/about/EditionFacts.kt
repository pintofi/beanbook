package dev.beancount.app.about

/**
 * The one fact About states that differs between editions.
 *
 * Only the name. The two editions no longer differ in how they update either: Play delivers
 * updates here, and the Expert edition is installed from a file, but neither contains a
 * downloader or requests the permission installing would need (B40, D3).
 */
internal object EditionFacts {
    const val NAME = "Play"
}
