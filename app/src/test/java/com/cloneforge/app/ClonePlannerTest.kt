package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClonePlannerTest {

    @Test
    fun create_trimsCloneNameAndBuildsDeterministicPackage() {
        val plan = ClonePlanner.create("com.example.app", "  Example Clone  ", 1)

        assertEquals("com.example.app", plan.sourcePackage)
        assertEquals("Example Clone", plan.cloneName)
        assertEquals(1, plan.cloneIndex)
        assertTrue(plan.clonePackage.startsWith("com.example.app.clone1"))
    }

    @Test
    fun createWithSuffix_appliesCustomPackageSuffix() {
        val plan = ClonePlanner.createWithSuffix(
            sourcePackage = "com.example.app",
            cloneName = "Work Profile",
            suffix = "work1",
            cloneIndex = 2
        )

        assertEquals("com.example.app.work1", plan.clonePackage)
        assertEquals("Work Profile", plan.cloneName)
        assertEquals(2, plan.cloneIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun create_rejectsBlankCloneName() {
        ClonePlanner.create("com.example.app", "   ", 1)
    }
}
