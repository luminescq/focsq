package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaUriPolicyTest {
    @Test
    fun acceptsTrustedHttpsHosts() {
        assertTrue(CaptchaUriPolicy.isAllowed("https://vk.com/captcha"))
        assertTrue(CaptchaUriPolicy.isAllowed("https://id.vk.ru/path?q=1"))
        assertTrue(CaptchaUriPolicy.isAllowed("https://calls.okcdn.ru/fb.do"))
    }

    @Test
    fun rejectsUntrustedOrInsecureHosts() {
        assertFalse(CaptchaUriPolicy.isAllowed("http://vk.com/captcha"))
        assertFalse(CaptchaUriPolicy.isAllowed("https://vk.com.attacker.example/captcha"))
        assertFalse(CaptchaUriPolicy.isAllowed("https://evilvk.com/captcha"))
        assertFalse(CaptchaUriPolicy.isAllowed("not a uri"))
    }
}
