package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageIdentityTest {

    @Test
    fun createsDeterministicClonePackage() {
        val first = PackageIdentity.createClonePackage("com.example.demo", 1)
        val second = PackageIdentity.createClonePackage("com.example.demo", 1)

        assertEquals(first, second)
        assertTrue(first.startsWith("com.example.demo.clone1"))
    }

    @Test
    fun differentCloneIndexesProduceDifferentPackages() {
        val first = PackageIdentity.createClonePackage("com.example.demo", 1)
        val second = PackageIdentity.createClonePackage("com.example.demo", 2)

        assertNotEquals(first, second)
    }

    @Test
    fun validatesCustomSuffix() {
        val result = PackageIdentity.validate("com.example.demo", "clone42")

        assertTrue(result.isSuccess)
        assertEquals("com.example.demo.clone42", result.getOrThrow())
    }

    @Test
    fun rejectsInvalidSuffix() {
        val result = PackageIdentity.validate("com.example.demo", "bad-suffix")

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsZeroCloneIndex() {
        val result = runCatching {
            PackageIdentity.createClonePackage("com.example.demo", 0)
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsOverlongPackage() {
        val longSuffix = "x".repeat(250)
        val result = PackageIdentity.validate("com.example.demo", longSuffix)

        assertTrue(result.isFailure)
    }

    @Test
    fun acceptsDotPrefixedSuffix() {
        val result = PackageIdentity.validate("com.example.demo", ".clone42")

        assertTrue(result.isSuccess)
        assertEquals("com.example.demo.clone42", result.getOrThrow())
    }
}
