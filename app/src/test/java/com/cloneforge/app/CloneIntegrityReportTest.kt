package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneIntegrityReportTest {

    @Test
    fun healthyReport_isHealthyWhenThereAreNoIssues() {
        val report = CloneIntegrityReport(
            checked = 3,
            healthy = 3,
            issues = emptyList(),
            signaturesChecked = 3,
            signaturesVerified = 3
        )

        assertTrue(report.isHealthy)
        assertEquals(0, report.checksumBaselinesCreated)
    }

    @Test
    fun issueReport_isNotHealthy() {
        val issue = CloneIntegrityIssue(
            clonePackage = "com.example.clone1",
            cloneName = "Example Clone",
            message = "SHA-256 checksum does not match the library record."
        )
        val report = CloneIntegrityReport(
            checked = 2,
            healthy = 1,
            issues = listOf(issue),
            signaturesChecked = 2,
            signaturesVerified = 2
        )

        assertFalse(report.isHealthy)
        assertEquals(1, report.issues.size)
        assertEquals("com.example.clone1", report.issues.first().clonePackage)
    }

    @Test
    fun baselineCount_isReportedSeparatelyFromHealthyCount() {
        val report = CloneIntegrityReport(
            checked = 4,
            healthy = 4,
            issues = emptyList(),
            signaturesChecked = 4,
            signaturesVerified = 4,
            checksumBaselinesCreated = 2
        )

        assertTrue(report.isHealthy)
        assertEquals(4, report.healthy)
        assertEquals(2, report.checksumBaselinesCreated)
    }
}
