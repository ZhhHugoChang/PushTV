package com.example.pushtv.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun removesCredentialsAndTokens() {
        val source = "Authorization: Bearer abc123 ghp_123456789012345678901234 https://user:pass@example.com/x?token=secret"
        val result = LogSanitizer.sanitize(source)

        assertFalse(result.contains("abc123"))
        assertFalse(result.contains("ghp_123456789012345678901234"))
        assertFalse(result.contains("user:pass"))
        assertFalse(result.contains("token=secret"))
        assertTrue(result.contains("<redacted>"))
    }
}
