package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkCloneVerifierTest {
    @Test
    fun descriptorVerificationRequiresNoOriginalReferences() {
        val verifier = DexDescriptorVerifier()
        val result = verifier.verifyBytes(
            "Lcom/example/clone1/Foo;".toByteArray(),
            "com.example",
            "com.example.clone1"
        )

        assertEquals(0, result.sourceOccurrences)
        assertTrue(result.cloneOccurrences > 0)
        assertTrue(result.verified)
    }
}
