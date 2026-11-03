package ru.documentvault.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginLimiterTest {
    @Test fun `limits attempts by address and expires them`() {
        val limiter = LoginLimiter(maxAttempts = 2, windowSeconds = 60)
        assertTrue(limiter.allow("first", 100))
        assertTrue(limiter.allow("first", 101))
        assertFalse(limiter.allow("first", 102))
        assertTrue(limiter.allow("second", 102))
        assertTrue(limiter.allow("first", 160))
        assertTrue(limiter.allow("first", 161))
    }
}
