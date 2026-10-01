package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexDescriptorVerifierTest {
    private val verifier = DexDescriptorVerifier()

    @Test
    fun verifiesCloneDescriptorWhenSourceDescriptorIsGone() {
        val data = "header Lcom/example/app/clone1; trailer".toByteArray()

        val result = verifier.verifyBytes(data, "com.example.app", "com.example.app.clone1")

        assertEquals(0, result.sourceOccurrences)
        assertEquals(1, result.cloneOccurrences)
        assertTrue(result.verified)
    }

    @Test
    fun failsWhenOriginalDescriptorRemains() {
        val data = "Lcom/example/app; Lcom/example/app/clone1;".toByteArray()

        val result = verifier.verifyBytes(data, "com.example.app", "com.example.app.clone1")

        assertEquals(1, result.sourceOccurrences)
        assertEquals(1, result.cloneOccurrences)
        assertFalse(result.verified)
    }

    @Test
    fun handlesMultipleCloneDescriptorOccurrences() {
        val data = "Lcom/example/app/clone1;Lcom/example/app/clone1;".toByteArray()

        val result = verifier.verifyBytes(data, "com.example.app", "com.example.app.clone1")

        assertEquals(0, result.sourceOccurrences)
        assertEquals(2, result.cloneOccurrences)
        assertTrue(result.verified)
    }
}
