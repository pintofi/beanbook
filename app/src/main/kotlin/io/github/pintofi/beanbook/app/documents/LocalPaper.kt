package io.github.pintofi.beanbook.app.documents

import android.icu.util.LocaleData
import android.icu.util.ULocale
import io.github.pintofi.beanbook.core.domain.PageSize
import java.util.Locale

/**
 * The paper this device's region uses: Letter where the standard sheet is 279 mm tall, A4 everywhere
 * else and whenever the question cannot be answered. It only decides which of the two papers is
 * offered second; nothing is chosen for the person.
 */
internal fun localPaper(locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): PageSize =
    runCatching {
        val sheet = LocaleData.getPaperSize(ULocale.forLocale(locale))
        if (sheet.height == LETTER_HEIGHT_MM) PageSize.LETTER else PageSize.A4
    }.getOrDefault(PageSize.A4)

private const val LETTER_HEIGHT_MM = 279
