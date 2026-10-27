package com.zcode.watchrec

import org.junit.Assert.*
import org.junit.Test

class TingwuLoginUrlsTest {
    @Test fun fallbackKeepsTheOfficialCallbackButRejectsUntrustedDestinations() {
        val valid = "https://account.aliyun.com/login/login.htm?oauth_callback=https%3A%2F%2Ftingwu.aliyun.com%2Fhome"
        assertEquals(valid, TingwuLoginUrls.fallback(valid))
        listOf(null, "null", "http://account.aliyun.com/login/login.htm", "https://account.aliyun.com.evil.test/login/login.htm",
            "https://account.aliyun.com@evil.test/login/login.htm", "https://user:pass@account.aliyun.com/login/login.htm",
            "https://account.aliyun.com:8443/login/login.htm", "https://account.aliyun.com/other", valid + "#ticket",
            "https://account.aliyun.com/login/login.htm?q=" + "x".repeat(8192)).forEach { assertNull(TingwuLoginUrls.fallback(it)) }
    }

    @Test fun topLevelNavigationStaysWithinTheExistingOfficialHttpsFlow() {
        listOf("tingwu.aliyun.com", "account.aliyun.com", "login.aliyun.com", "passport.aliyun.com", "login.taobao.com")
            .forEach { assertTrue(TingwuLoginUrls.official("https://$it/login?callback=synthetic")) }
        listOf("javascript:alert(1)", "file:///local", "https://evil.test/", "http://tingwu.aliyun.com/",
            "https://tingwu.aliyun.com:8443/", "https://user@tingwu.aliyun.com/").forEach { assertFalse(TingwuLoginUrls.official(it)) }
    }
}
