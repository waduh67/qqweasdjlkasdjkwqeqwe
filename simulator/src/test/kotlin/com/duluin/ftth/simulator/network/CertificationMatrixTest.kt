package com.duluin.ftth.simulator.network

import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.CertificationPhaseResult
import com.duluin.ftth.contract.CertificationVerdict
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CertificationMatrixTest {
    @Test
    fun `evidence is emitted only after every required phase passes`() {
        val report = CertificationMatrixRunner().certify(
            StandaloneSimulatorCertificationSubject(SimulatorProfiles.simulator),
        )

        assertEquals(CertificationVerdict.CERTIFIED_BY_TEST, report.verdict)
        assertEquals(CertificationPhase.entries.toSet(), report.phases.map { it.phase }.toSet())
        assertTrue(report.phases.all(CertificationPhaseResult::passed))
        val evidenceIdentity = assertNotNull(report.evidenceIdentity)

        val path = Files.createTempFile("task-17-matrix", ".json")
        val reports = listOf(report)
        CertificationMatrixWriter.write(path, reports)
        val json = path.readText()
        assertTrue(json.contains("\"status\":\"CERTIFIED_BY_TEST\""))
        assertTrue(json.contains(evidenceIdentity))
    }

    @Test
    fun `hardware fingerprints remain provisional even when fixture phases pass`() {
        val hardware = SimulatorProfiles.routerOs.copy(origin = FingerprintOrigin.HARDWARE)

        val report = CertificationMatrixRunner().certify(StandaloneSimulatorCertificationSubject(hardware))

        assertEquals(CertificationVerdict.PROVISIONAL, report.verdict)
        assertNull(report.evidenceIdentity)
        assertFalse(CertificationMatrixWriter.toJson(listOf(report)).contains("evidenceIdentity"))
    }

    @Test
    fun `failed phase suppresses evidence identity`() {
        val report = CertificationMatrixRunner().certify(
            StandaloneSimulatorCertificationSubject(
                SimulatorProfiles.simulator,
                DeterministicFaultScript(mapOf(SimulatorFaultPoint.BEFORE_TRANSIT_MUTATION to 1)),
            ),
        )

        assertEquals(CertificationVerdict.PROVISIONAL, report.verdict)
        assertNull(report.evidenceIdentity)
        assertTrue(report.phases.any { !it.passed })
    }
}
