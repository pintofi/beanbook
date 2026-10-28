package io.github.pintofi.beanbook.app.onboarding

import io.github.pintofi.beanbook.core.data.Json
import io.github.pintofi.beanbook.core.domain.LedgerBlueprint
import java.io.File
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What creating the ledger is doing, as the Finish page shows it. */
sealed interface Creation {
    data object Idle : Creation
    data class Working(val what: String) : Creation
    data class Done(val projectId: String, val summary: String, val imported: Boolean = false) : Creation
    data class Failed(val message: String) : Creation
}

/**
 * The first-run setup: its answers, which page it is on, and the creation it ends in.
 *
 * Held by the application, so turning the phone never touches it, and written to a small file
 * after every change so a killed process picks up where it was. Nothing is written under
 * `projects/` until the ledger is created, and the file is deleted when it is, or when somebody
 * starts over: it can hold opening balances, which are financial data, and has no business
 * staying behind.
 */
class NewLedgerSetup(private val file: File) {

    private val saved: SetupDraft? = runCatching {
        file.takeIf { it.isFile }?.let { SetupDraft.fromJson(Json.parse(it.readText())) }
    }.getOrNull()

    private val _draft = MutableStateFlow(saved ?: fresh())
    val draft: StateFlow<SetupDraft> = _draft.asStateFlow()

    /** Whether the notification permission has been given, as the Get set step shows it. */
    val notificationsGranted = MutableStateFlow(false)

    private val _creation = MutableStateFlow<Creation>(Creation.Idle)
    val creation: StateFlow<Creation> = _creation.asStateFlow()

    /** A setup that was left part way and can be picked up. */
    val resumable: Boolean get() = saved != null && saved.step != SetupStep.WELCOME

    fun update(change: (SetupDraft) -> SetupDraft) {
        _draft.value = change(_draft.value)
        persist()
    }

    fun go(step: SetupStep) = update { it.copy(step = step) }

    /** Starts a setup for one more ledger: no welcome, the name already typed, nothing left from an earlier try. */
    fun begin(name: String) {
        reset()
        update { it.copy(name = name.trim(), step = SetupStep.WAYS_IN) }
    }

    fun creating(state: Creation) {
        _creation.value = state
    }

    /** The ledger exists: the saved answers (opening balances among them) are deleted, the page stays. */
    fun finished(projectId: String, summary: String, imported: Boolean = false) {
        _creation.value = Creation.Done(projectId, summary, imported)
        runCatching { file.delete() }
    }

    /** Forgets every answer and the file they were saved in. */
    fun reset() {
        _creation.value = Creation.Idle
        _draft.value = fresh()
        runCatching { file.delete() }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(_draft.value.toJson().write())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        fun fresh() = SetupDraft(mainCurrency = SetupDraft.deviceCurrency())

        /** `Euro` for `EUR`, in the device's language; nothing for a code that is not a currency. */
        fun commodityNames(codes: Collection<String>, locale: Locale = Locale.getDefault()): Map<String, String> =
            codes.mapNotNull { code ->
                runCatching { Currency.getInstance(code).getDisplayName(locale) }.getOrNull()
                    ?.takeIf { it.isNotBlank() && it != code }
                    ?.let { code to it }
            }.toMap()

        /** The sentence the Finish page shows under the stamp. */
        fun summary(blueprint: LedgerBlueprint): String = buildString {
            val accounts = blueprint.plannedAccounts().size
            append("main.beancount and 4 more files, $accounts accounts, ${blueprint.mainCurrency}.")
            val others = blueprint.commodityCodes().filter { it != blueprint.mainCurrency }
            if (others.isNotEmpty()) append(" Also declares ${others.joinToString(", ")}.")
            if (blueprint.openingBalances.isNotEmpty()) {
                append(" Opening balances for ${blueprint.openingBalances.size} account")
                append(if (blueprint.openingBalances.size == 1) "." else "s.")
            }
        }
    }
}
