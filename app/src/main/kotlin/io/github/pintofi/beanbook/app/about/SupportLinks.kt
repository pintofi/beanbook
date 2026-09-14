package io.github.pintofi.beanbook.app.about

/** Where the editions are explained, in the public repository. Nothing is fetched from it. */
internal const val EDITIONS_URL = "https://github.com/PintoFi/beanbook/blob/main/docs/editions.md"

/** Another edition, as the one running describes it. */
internal data class OtherEdition(
    val name: String,
    val summary: String,
    val differences: List<String>,
    val url: String,
)
