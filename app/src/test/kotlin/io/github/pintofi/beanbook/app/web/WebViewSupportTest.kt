package io.github.pintofi.beanbook.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSupportTest {
    @Test fun `the WebView on the Android 10 emulator image is too old`() {
        assertTrue(WebViewSupport.tooOld(74))
    }

    @Test fun `the line is at the minimum and not above it`() {
        assertTrue(WebViewSupport.tooOld(WebViewSupport.MIN_MAJOR - 1))
        assertFalse(WebViewSupport.tooOld(WebViewSupport.MIN_MAJOR))
        assertFalse(WebViewSupport.tooOld(135))
    }

    @Test fun `a WebView that does not say its version is not called old`() {
        assertFalse(WebViewSupport.tooOld(null))
    }
}
