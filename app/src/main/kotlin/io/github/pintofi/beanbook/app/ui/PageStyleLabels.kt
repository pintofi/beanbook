package io.github.pintofi.beanbook.app.ui

import io.github.pintofi.beanbook.core.domain.PageStyle

/** What a page style is called on screen. The review's style control lists every entry of [PageStyle]. */
internal fun PageStyle.label(): String = when (this) {
    PageStyle.COLOUR -> "Colour"
    PageStyle.DOCUMENT -> "Document"
    PageStyle.GREYSCALE -> "Greyscale"
    PageStyle.BLACK_AND_WHITE -> "Black and white"
}
