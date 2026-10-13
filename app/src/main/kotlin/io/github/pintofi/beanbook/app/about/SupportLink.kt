package io.github.pintofi.beanbook.app.about

import androidx.annotation.DrawableRes

/** Where voluntary support goes, and the icon for its row in About (Expert edition only). */
internal data class SupportLink(val name: String, val url: String, @DrawableRes val icon: Int)
