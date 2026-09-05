package io.github.pintofi.beanbook.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest reader and writer.
 *
 * A backup manifest is the only thing standing between a corrupted archive and a restore
 * that quietly loses data, so this is held to the awkward cases rather than the happy path.
 */
class JsonTest {

    private fun roundTrip(value: Json): Json? = Json.parse(value.write())

    @Test
    fun `an object round-trips with every value type intact`() {
        val manifest = Json.obj(
            "schema_version" to Json.of(1),
            "project_id" to Json.of("my-ledger"),
            "encrypted" to Json.of(false),
            "files" to Json.arr(
                listOf(
                    Json.obj("path" to Json.of("main.beancount"), "size" to Json.of(120L)),
                ),
            ),
            "notes" to Json.Null,
        )

        assertEquals(manifest, roundTrip(manifest))
    }

    @Test
    fun `a path with quotes, backslashes and newlines survives`() {
        // Beancount filenames can contain almost anything; a manifest that mangles one turns
        // into a restore that cannot find the file it lists.
        val awkward = """a "file"\with
        newlines	and tabs"""
        val value = Json.obj("path" to Json.of(awkward))

        assertEquals(awkward, (roundTrip(value) as Json.Obj).string("path"))
    }

    @Test
    fun `unicode outside ASCII survives`() {
        val value = Json.obj("title" to Json.of("Hörbücher · 日本語 · émoji 🎉"))

        assertEquals(
            "Hörbücher · 日本語 · émoji 🎉",
            (roundTrip(value) as Json.Obj).string("title"),
        )
    }

    @Test
    fun `whole numbers are written without a decimal point`() {
        // A size of "120.0" would be read back fine but looks wrong to anyone opening the
        // manifest, and this file is meant to be readable.
        assertTrue(Json.of(120L).write() == "120", Json.of(120L).write())
        assertEquals(120L, (Json.parse("120") as Json.Num).value.toLong())
    }

    @Test
    fun `a large file size is exact, not rounded`() {
        // Doubles hold integers exactly up to 2^53; a 4 GB archive is nowhere near that, and
        // this pins the property rather than assuming it.
        val fourGigabytes = 4L * 1024 * 1024 * 1024
        val value = Json.obj("size" to Json.of(fourGigabytes))

        assertEquals(fourGigabytes, (roundTrip(value) as Json.Obj).long("size"))
    }

    @Test
    fun `nested arrays of objects are read back in order`() {
        val value = Json.obj(
            "files" to Json.arr(
                (1..3).map { Json.obj("path" to Json.of("file-$it")) },
            ),
        )

        val files = (roundTrip(value) as Json.Obj).array("files").orEmpty()
        assertEquals(listOf("file-1", "file-2", "file-3"), files.map { (it as Json.Obj).string("path") })
    }

    @Test
    fun `empty objects and arrays are not confused with missing ones`() {
        val value = Json.obj("files" to Json.arr(emptyList()), "meta" to Json.Obj(emptyMap()))

        val parsed = roundTrip(value) as Json.Obj
        assertEquals(emptyList(), parsed.array("files"))
        assertEquals(emptyMap(), parsed.obj("meta")?.fields)
        assertNull(parsed.array("absent"))
    }

    @Test
    fun `damaged JSON is refused rather than half-read`() {
        listOf(
            "",
            "{",
            "{\"a\":}",
            "{\"a\" 1}",
            "[1, 2",
            "{\"a\":1} trailing",
            "{\"a\":\"unterminated}",
            "nul",
        ).forEach { broken ->
            assertNull(Json.parse(broken), "accepted \"$broken\"")
        }
    }

    @Test
    fun `a duplicated key is refused rather than silently resolved`() {
        assertNull(Json.parse("""{"schema_version":1,"schema_version":2}"""))
    }

    @Test
    fun `keys are written in a stable order, so two identical manifests match`() {
        val one = Json.obj("b" to Json.of(1), "a" to Json.of(2)).write()
        val two = Json.obj("a" to Json.of(2), "b" to Json.of(1)).write()

        assertEquals(one, two)
    }

    @Test
    fun `the manifest is readable, not one long line`() {
        val text = Json.obj(
            "schema_version" to Json.of(1),
            "files" to Json.arr(listOf(Json.obj("path" to Json.of("main.beancount")))),
        ).write()

        assertTrue(text.contains("\n"), text)
        assertTrue(text.contains("  \"schema_version\": 1"), text)
    }
}
