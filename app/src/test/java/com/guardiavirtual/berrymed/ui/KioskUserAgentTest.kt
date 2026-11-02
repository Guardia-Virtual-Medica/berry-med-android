// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class KioskUserAgentTest {
    @Test
    fun aTabletWebViewLooksLikeChrome() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 13; SM-X200 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.219 Safari/537.36",
            KioskUserAgent.asChrome(
                "Mozilla/5.0 (Linux; Android 13; SM-X200 Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.219 Safari/537.36",
            ),
        )
    }

    @Test
    fun aPhoneWebViewKeepsMobile() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.58 Mobile Safari/537.36",
            KioskUserAgent.asChrome(
                "Mozilla/5.0 (Linux; Android 15; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36",
            ),
        )
    }

    @Test
    fun chromeStaysTheSame() {
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        assertEquals(chrome, KioskUserAgent.asChrome(chrome))
    }
}
