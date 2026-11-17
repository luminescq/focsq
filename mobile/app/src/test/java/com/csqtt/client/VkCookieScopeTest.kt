package com.csqtt.client

import org.junit.Assert.*
import org.junit.Test

class VkCookieScopeTest {
    @Test fun onlyVkDomainsCanBeCleared() {
        assertTrue(vkCookieDeletionHeaders("https://captcha.example/", "session=123").isEmpty())
        assertTrue(vkCookieDeletionHeaders("https://vk.com.example/", "session=123").isEmpty())
        assertTrue(vkCookieDeletionHeaders("https://vk.com/", null).isEmpty())
    }

    @Test fun clearsHostOnlyAndParentDomainCookiesWithoutTheirValues() {
        val headers = vkCookieDeletionHeaders("https://login.vk.com/", "remixsid=private-value; other=second-value")
        assertEquals(6, headers.size)
        assertTrue(headers.any { it.endsWith("Domain=vk.com") })
        assertTrue(headers.any { it.endsWith("Domain=login.vk.com") })
        assertTrue(headers.all { it.contains("Max-Age=0") && !it.contains("private-value") && !it.contains("second-value") })
        assertFalse(headers.any { it.endsWith("Domain=com") })
    }
}
