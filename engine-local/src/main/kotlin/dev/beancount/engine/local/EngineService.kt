package dev.beancount.engine.local

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * The engine, in its own process.
 *
 * Declared with `android:process=":engine"`, so CPython, Beancount and Fava run at arm's
 * length from the Compose UI. Two reasons, both real:
 *
 *  1. **Licensing (risk R9).** Beancount is GPL-2.0-only; AndroidX and Compose are
 *     Apache-2.0, which cannot be combined with GPLv2. Communicating with Beancount as a
 *     separate program is materially different from linking it into a binary full of
 *     Apache-2.0 code. This does not make the position airtight — same APK, same UID — and
 *     docs/architecture.md §7.1 says so rather than claiming victory.
 *  2. **Crash isolation.** Expert-edition plugins are unsandboxed by design. A plugin that
 *     brings down the interpreter now takes the engine process only; the UI survives and
 *     can offer safe mode.
 *
 * This class is deliberately thin: it owns no policy, holds no state beyond the Python
 * modules, and never writes to a project. Journalling, snapshots and the atomic rename all
 * stay in `ProjectWriter`, in the UI process, where they were tested.
 */
class EngineService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IEngineService.Stub() {

        override fun startEngine(ledgerPath: String, token: String): String = guarded {
            python().getModule("engine").callAttr("start", ledgerPath, token).toString()
        }

        override fun stopEngine(): String = guarded {
            python().getModule("engine").callAttr("stop").toString()
        }

        override fun engineVersions(): String = guarded {
            python().getModule("engine").callAttr("versions").toString()
        }

        override fun inspectToFile(ledgerPath: String, journalLimit: Int, outPath: String): String =
            guarded {
                val json = python().getModule("ledger")
                    .callAttr("inspect", ledgerPath, journalLimit)
                    .toString()
                writeOut(outPath, json)
                "ok"
            }

        override fun validate(mainFile: String): String = guarded {
            python().getModule("writer").callAttr("validate", mainFile).toString()
        }

        override fun entrySlice(mainFile: String, targetFile: String, line: Int): String =
            guarded {
                python().getModule("writer")
                    .callAttr("entry_slice", mainFile, targetFile, line)
                    .toString()
            }

        override fun transformToFile(
            op: String,
            filePath: String,
            fromLine: Int,
            toLine: Int,
            newText: String?,
            outPath: String,
        ): String = guarded {
            val module = python().getModule("writer")
            val json = when (op) {
                "replace" -> module.callAttr(
                    "replace_lines", filePath, fromLine, toLine, newText.orEmpty(),
                ).toString()
                "delete" -> module.callAttr("delete_lines", filePath, fromLine, toLine).toString()
                else -> return@guarded "error: unknown op $op"
            }
            val root = org.json.JSONObject(json)
            if (!root.optBoolean("ok")) return@guarded "error: ${root.optString("error")}"
            writeOut(outPath, root.getString("content"))
            "ok"
        }
    }

    private fun python() = PythonRuntime.ensureStarted(this)

    /**
     * A crash in the engine process must arrive at the caller as a message, not as a dead
     * Binder: the UI has to be able to say *why* it failed.
     */
    private inline fun guarded(block: () -> String): String =
        try {
            block()
        } catch (error: Throwable) {
            Log.w(TAG, "engine call failed", error)
            "error: ${error.message ?: error.javaClass.simpleName}"
        }

    private fun writeOut(path: String, content: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        // Large payloads travel via the app's own cache rather than Binder, whose ~1 MB
        // transaction limit a 7.7 MB ledger would blow instantly.
        file.writeText(content)
    }

    private companion object {
        const val TAG = "EngineService"
    }
}
