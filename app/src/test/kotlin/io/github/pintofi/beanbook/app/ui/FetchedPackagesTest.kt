package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fetched-packages list as packages, not folders (device audit 2026-10-10). */
class FetchedPackagesTest {

    @Test fun aPackageAndItsMetadataFolderAreOneRowWithAVersion() {
        assertEquals(
            listOf(FetchedPackage("numpy", "2.1.3")),
            groupFetched(listOf("numpy", "numpy-2.1.3.dist-info", "numpy.libs")),
        )
    }

    @Test fun theImportNameAndThePublishedNameAreMatchedAcrossUnderscoresAndHyphens() {
        assertEquals(
            listOf(FetchedPackage("curl_cffi", "0.7.4")),
            groupFetched(listOf("curl_cffi", "curl_cffi-0.7.4.dist-info", "curl-cffi.libs")),
        )
    }

    @Test fun metadataAloneStillNamesAPackage() {
        // A namespace package can unpack with no folder of its own name.
        assertEquals(
            listOf(FetchedPackage("certifi", "2026.7.22")),
            groupFetched(listOf("certifi-2026.7.22.dist-info")),
        )
    }

    @Test fun aFolderWithNoMetadataIsListedWithoutAVersion() {
        assertEquals(listOf(FetchedPackage("beangrow", null)), groupFetched(listOf("beangrow")))
    }

    @Test fun cachesAndToolFoldersAreNotPackages() {
        assertEquals(
            listOf(FetchedPackage("six", null)),
            groupFetched(listOf("__pycache__", "bin", ".lock", "six.py")),
        )
    }

    @Test fun theListIsInNameOrderWhateverOrderTheFolderGaveIt() {
        assertEquals(
            listOf("cffi", "numpy", "Pandas"),
            groupFetched(listOf("pandas", "Pandas-2.2.dist-info", "numpy", "cffi")).map { it.name },
        )
    }

    @Test fun twentyFoldersBecomeAHandfulOfRows() {
        val listing = listOf(
            "certifi", "certifi-2026.7.22.dist-info",
            "cffi", "cffi-1.17.1.dist-info", "_cffi_backend.cpython-313-aarch64-linux-android.so",
            "curl_cffi", "curl_cffi-0.7.4.dist-info", "curl-cffi.libs",
            "numpy", "numpy-2.1.3.dist-info", "numpy.libs",
            "pandas", "pandas-2.2.3.dist-info",
            "pytz", "pytz-2025.1.dist-info",
            "six.py", "six-1.17.0.dist-info",
            "__pycache__", "bin",
        )
        val rows = groupFetched(listing)

        assertEquals(
            listOf("certifi", "cffi", "curl_cffi", "numpy", "pandas", "pytz", "six"),
            rows.map { it.name },
        )
    }
}
