package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.packages.androidWheelFits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** PyPI's own Android wheels are taken when they fit this interpreter and device (B82). No network. */
@RunWith(AndroidJUnit4::class)
class AndroidWheelTagTest {

    @Test fun curlCffisPublishedAndroidWheelFitsAnArm64Phone() {
        assertTrue(androidWheelFits("curl_cffi-0.16.3-cp313-cp313-android_24_arm64_v8a.whl", "arm64_v8a"))
    }

    @Test fun theWrongArchitectureOrInterpreterDoesNot() {
        assertFalse(androidWheelFits("curl_cffi-0.16.3-cp313-cp313-android_24_arm64_v8a.whl", "x86_64"))
        assertFalse(androidWheelFits("curl_cffi-0.16.3-cp314-cp314-android_24_arm64_v8a.whl", "arm64_v8a"))
        assertFalse(androidWheelFits("curl_cffi-0.16.3-cp310-abi3-manylinux2014_aarch64.whl", "arm64_v8a"))
    }

    @Test fun aStableAbiWheelFromAnOlderPythonFits() {
        assertTrue(androidWheelFits("thing-1.0-cp310-abi3-android_21_x86_64.whl", "x86_64"))
    }

    @Test fun aWheelNeedingANewerAndroidThanTheAppsFloorDoesNot() {
        assertFalse(androidWheelFits("thing-1.0-cp313-cp313-android_33_arm64_v8a.whl", "arm64_v8a"))
    }
}
