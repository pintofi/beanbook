package io.github.pintofi.beanbook.app.engine

import io.github.pintofi.beanbook.core.data.TemplateStore
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The entry templates of the open project.
 *
 * Reading and writing happen off the main thread — it is a small file, but it is still a
 * file, and the entry form must not stutter because someone saved a template.
 */
class TemplatesState(
    private val store: TemplateStore,
    private val scope: CoroutineScope,
) {
    private val _templates = MutableStateFlow<List<EntryTemplate>>(emptyList())
    val templates: StateFlow<List<EntryTemplate>> = _templates.asStateFlow()

    private var projectId: String? = null

    fun open(projectId: String) {
        this.projectId = projectId
        reload()
    }

    fun save(template: EntryTemplate) = mutate { store.save(it, template) }

    fun delete(name: String) = mutate { store.delete(it, name) }

    private fun mutate(action: (String) -> Unit) {
        val id = projectId ?: return
        scope.launch {
            withContext(Dispatchers.IO) { action(id) }
            reload()
        }
    }

    private fun reload() {
        val id = projectId ?: return
        scope.launch {
            _templates.value = withContext(Dispatchers.IO) { store.load(id) }
        }
    }
}
