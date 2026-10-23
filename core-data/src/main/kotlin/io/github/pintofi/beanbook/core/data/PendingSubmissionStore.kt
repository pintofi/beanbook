package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.Cost
import io.github.pintofi.beanbook.core.domain.PostingDraft
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** An entry sent, or about to be sent, to a remote Fava, with the key that makes a resend safe. */
data class PendingSubmission(val draft: TransactionDraft, val key: String)

/**
 * The one remote entry that may or may not have reached the server, kept on disk.
 *
 * Fava does not deduplicate, so the idempotency key is the only thing that lets a retry ask
 * "is it already there?" instead of sending a second copy. Held only in memory, the key and the
 * entry were gone when the process died, and typing the entry again made a new key. The file is
 * written before the entry is sent and removed once the server has confirmed it or the person
 * has discarded it, so a killed app finds it again.
 *
 * It sits in the app's private state directory, like the rest of the remote settings, and it
 * is written the way the ledger is: to a temporary file, flushed, then moved into place, so a
 * power cut leaves either the old file or the new one and never half of one.
 */
class PendingSubmissionStore(private val file: File) {

    fun save(pending: PendingSubmission) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(encode(pending).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** What was saved, or null when nothing was, or the file is not one this app wrote. */
    fun load(): PendingSubmission? =
        file.takeIf { it.isFile }?.let { decode(runCatching { it.readText() }.getOrDefault("")) }

    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    internal companion object {
        private const val VERSION = 1

        fun encode(pending: PendingSubmission): String = Json.obj(
            "version" to Json.of(VERSION),
            "key" to Json.of(pending.key),
            "draft" to draftJson(pending.draft),
        ).write()

        fun decode(text: String): PendingSubmission? = runCatching {
            val root = Json.parse(text) as? Json.Obj ?: return null
            if (root.long("version") != VERSION.toLong()) return null
            val key = root.string("key")?.takeIf { it.isNotBlank() } ?: return null
            PendingSubmission(draftFrom(root.obj("draft") ?: return null), key)
        }.getOrNull()

        private fun strings(items: List<String>) = Json.arr(items.map { Json.of(it) })

        private fun strings(json: Json.Obj, name: String) =
            json.array(name).orEmpty().map { (it as Json.Str).value }

        private fun map(values: Map<String, String>) =
            Json.Obj(values.mapValues { Json.of(it.value) })

        private fun map(json: Json.Obj, name: String): Map<String, String> =
            json.obj(name)?.fields.orEmpty().mapValues { (it.value as Json.Str).value }

        private fun amountJson(amount: Amount) = Json.obj(
            "number" to Json.of(amount.number.toPlainString()),
            "currency" to Json.of(amount.currency),
        )

        private fun amountFrom(json: Json.Obj?): Amount? =
            json?.let { Amount(BigDecimal(it.string("number")!!), it.string("currency")!!) }

        private fun optional(name: String, value: String?) =
            if (value == null) emptyList() else listOf(name to Json.of(value))

        private fun draftJson(draft: TransactionDraft) = Json.Obj(
            (
                listOf(
                    "date" to Json.of(draft.date),
                    "flag" to Json.of(draft.flag),
                    "tags" to strings(draft.tags),
                    "links" to strings(draft.links),
                    "metadata" to map(draft.metadata),
                    "postings" to Json.arr(draft.postings.map(::postingJson)),
                ) + optional("payee", draft.payee) + optional("narration", draft.narration)
                ).toMap(),
        )

        private fun draftFrom(json: Json.Obj) = TransactionDraft(
            date = json.string("date")!!,
            flag = json.string("flag")!!,
            payee = json.string("payee"),
            narration = json.string("narration"),
            tags = strings(json, "tags"),
            links = strings(json, "links"),
            metadata = map(json, "metadata"),
            postings = json.array("postings").orEmpty().map { postingFrom(it as Json.Obj) },
        )

        private fun postingJson(posting: PostingDraft) = Json.Obj(
            (
                listOf("account" to Json.of(posting.account), "metadata" to map(posting.metadata)) +
                    listOfNotNull(
                        posting.amount?.let { "amount" to amountJson(it) },
                        posting.price?.let { "price" to amountJson(it) },
                        posting.cost?.let { "cost" to costJson(it) },
                    ) + optional("comment", posting.comment) + optional("details", posting.details)
                ).toMap(),
        )

        private fun postingFrom(json: Json.Obj) = PostingDraft(
            account = json.string("account")!!,
            amount = amountFrom(json.obj("amount")),
            cost = json.obj("cost")?.let {
                Cost(amountFrom(it.obj("amount"))!!, it.string("date"), it.string("label"))
            },
            price = amountFrom(json.obj("price")),
            comment = json.string("comment"),
            metadata = map(json, "metadata"),
            details = json.string("details"),
        )

        private fun costJson(cost: Cost) = Json.Obj(
            (
                listOf("amount" to amountJson(cost.amount)) +
                    optional("date", cost.date) + optional("label", cost.label)
                ).toMap(),
        )
    }
}
