package io.github.pintofi.beanbook.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which tab of Documents is showing, held above the screens that can change it.
 *
 * Home's card for a document that is not linked has to send people to Organize. The app builds a
 * fresh copy of the surface content for each destination it moves to, so a value kept inside it
 * is lost on the way: Home set it, and the Documents screen it had just opened never saw it.
 */
class DocumentsTabState {
    var tab by mutableStateOf(DocumentsMode.ADD)
}

val LocalDocumentsTab = staticCompositionLocalOf { DocumentsTabState() }
