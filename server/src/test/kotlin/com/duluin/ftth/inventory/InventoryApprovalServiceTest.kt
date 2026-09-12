package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class InventoryApprovalServiceTest {
    private val tenant = UUID.randomUUID()
    private val requester = UUID.randomUUID()
    private val custodian = UUID.randomUUID()
    private val firstApprover = UUID.randomUUID()
    private val secondApprover = UUID.randomUUID()

    private val tiers = listOf(
        ApprovalTierRule(1, 0, "Kepala Gudang", setOf(firstApprover)),
        ApprovalTierRule(2, 100, "Manajer Operasional", setOf(secondApprover)),
    )

    /**
     * Rakitan lengkap: matriks kebijakan disetel DULU oleh "administrator", baru layanannya
     * dipakai. Bentuk ini disengaja — ia memaksa tes menempuh jalur yang sama dengan produksi,
     * yaitu tier dibaca server dari matriks tenant, bukan diterima dari pemohon.
     */
    private class Fixture(
        val service: InventoryApprovalService,
        val policies: InventoryApprovalPolicyService,
        val audit: FakeInventoryApprovalAudit,
    )

    private fun fixture(
        clock: Clock = Clock.systemUTC(),
        expiry: Duration = Duration.ofHours(24),
        emergencyAllowed: Boolean = false,
        configuredTypes: List<InventoryApprovalType> = InventoryApprovalType.entries,
    ): Fixture {
        val audit = FakeInventoryApprovalAudit()
        val policies = InventoryApprovalPolicyService(FakeInventoryApprovalPolicies(), audit, RefusingIamApi)
        configuredTypes.forEach { type ->
            policies.configure(InventoryApprovalPolicyMatrix(tenant, type, expiry, emergencyAllowed, tiers))
        }
        val service = InventoryApprovalService(
            FakeInventoryApprovals(), policies, audit,
            InventoryMovementLedgerService(FakeInventoryLedger(), clock), clock,
        )
        return Fixture(service, policies, audit)
    }

    private fun request(
        service: InventoryApprovalService,
        amount: Long = 100,
        type: InventoryApprovalType = InventoryApprovalType.ADJUSTMENT,
        key: String = "request-1",
        emergencyReason: String? = null,
    ) = service.request(
        CreateInventoryApproval(tenant, type, amount, requester, custodian, key, "request-hash", null, emergencyReason),
    )

    private fun decision(approver: UUID, key: String = approver.toString(), value: InventoryApprovalDecision = InventoryApprovalDecision.APPROVE) =
        DecideInventoryApproval(tenant, approver, value, key, key)

    @Test fun `threshold requires two tiers and effect once`() {
        val service = fixture().service
        val approval = request(service)
        assertThat(service.decide(approval.approvalId, decision(firstApprover)).status).isEqualTo(InventoryApprovalStatus.PENDING)
        val complete = service.decide(approval.approvalId, decision(secondApprover))
        assertThat(complete.status).isEqualTo(InventoryApprovalStatus.APPROVED)
        assertThat(service.effects(tenant)).hasSize(1)
        assertThat(service.decide(approval.approvalId, decision(secondApprover)).status).isEqualTo(InventoryApprovalStatus.APPROVED)
        assertThat(service.effects(tenant)).hasSize(1)
    }

    @Test fun `requester and custodian cannot approve`() {
        val service = fixture().service
        val approval = request(service, 1)
        assertThatThrownBy { service.decide(approval.approvalId, decision(requester)) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service.decide(approval.approvalId, decision(custodian)) }.isInstanceOf(ValidationException::class.java)
    }

    @Test fun `same key different payload conflicts and rejection is rework for variance`() {
        val service = fixture().service
        val approval = service.request(
            CreateInventoryApproval(tenant, InventoryApprovalType.COUNT_VARIANCE, 1, requester, null, "variance-1", "hash-1"),
        )
        service.decide(approval.approvalId, decision(firstApprover, "d-1", InventoryApprovalDecision.REJECT))
        assertThat(service.get(approval.approvalId)!!.status).isEqualTo(InventoryApprovalStatus.REWORK_REQUIRED)
        assertThatThrownBy {
            service.decide(approval.approvalId, decision(firstApprover, "d-1", InventoryApprovalDecision.APPROVE).copy(operationHash = "other"))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test fun `expired approval is deterministic`() {
        val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        // 5 menit adalah masa berlaku TERPENDEK yang diizinkan matriks; lebih pendek dari itu
        // dan approver di zona waktu lain tidak pernah sempat membuka antreannya.
        val service = fixture(clock, expiry = Duration.ofMinutes(5)).service
        val approval = request(service, 1, InventoryApprovalType.LOSS, "loss-1")
        clock.now = clock.now.plusSeconds(301)
        assertThatThrownBy { service.decide(approval.approvalId, decision(firstApprover)) }.isInstanceOf(ConflictException::class.java)
        assertThat(service.get(approval.approvalId)!!.status).isEqualTo(InventoryApprovalStatus.EXPIRED)
    }

    /**
     * Penyapu tenggat: yang lewat waktu DITANDAI kedaluwarsa walau tidak ada seorang pun yang
     * membukanya. Tanpa langkah ini, permintaan menggantung PENDING selamanya dan mutasi stok
     * yang digantungnya tidak pernah berlaku MAUPUN dibatalkan.
     */
    @Test fun `sweeper expires overdue requests`() {
        val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val service = fixture(clock, expiry = Duration.ofMinutes(5)).service
        val approval = request(service, 1, InventoryApprovalType.SCRAP, "scrap-1")
        assertThat(service.expireOverdue(tenant, clock.now)).isEmpty()
        clock.now = clock.now.plusSeconds(301)
        assertThat(service.expireOverdue(tenant, clock.now)).containsExactly(approval.approvalId)
        assertThat(service.get(approval.approvalId)!!.status).isEqualTo(InventoryApprovalStatus.EXPIRED)
    }

    /**
     * Kebijakan yang dibekukan di permintaan datang dari MATRIKS TENANT, bukan dari pemohon.
     *
     * Perhatikan bahwa [CreateInventoryApproval] memang tidak lagi punya field kebijakan —
     * jadi separuh jaminannya ditegakkan kompilator. Yang diuji di sini adalah separuh sisanya:
     * isi snapshot benar-benar sama dengan matriks yang disetel administrator, sehingga
     * pemeriksaan "apakah kebijakannya berubah?" di kemudian hari punya pembanding yang jujur.
     */
    @Test fun `policy snapshot comes from the tenant matrix`() {
        val fixture = fixture()
        val approval = request(fixture.service, 100)
        val matrix = fixture.policies.effectiveFor(tenant, InventoryApprovalType.ADJUSTMENT)

        assertThat(approval.policy.tiers.map { it.number to it.approverIds })
            .containsExactly(1 to setOf(firstApprover), 2 to setOf(secondApprover))
        assertThat(approval.policySnapshotHash).isEqualTo(InventoryApprovalPolicyService.snapshotHash(matrix))
    }

    /**
     * Jenis yang BELUM disetel tenant memakai bawaan produk, dan bawaan itu tidak menunjuk
     * orang. Permintaannya WAJIB gagal keras di sini: kalau tier tanpa approver dibiarkan
     * lewat, permintaan besar cuma butuh persetujuan kepala gudang dan tidak ada yang tahu.
     */
    @Test fun `unconfigured type refuses to create a request`() {
        val service = fixture(configuredTypes = emptyList()).service
        assertThatThrownBy { request(service, 100) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("belum menunjuk approver")
    }

    @Test fun `emergency override is rejected when the matrix forbids it`() {
        val service = fixture(emergencyAllowed = false).service
        assertThatThrownBy { request(service, 100, emergencyReason = "gardu mati, ONT harus diganti malam ini") }
            .isInstanceOf(ValidationException::class.java)
    }

    /**
     * Override darurat melangkahi SELURUH rantai tier — karena itu jejaknya wajib ada di
     * transaksi yang sama. Jejak yang ditulis "nanti" adalah jejak yang bisa hilang justru pada
     * kasus yang paling perlu ditelusuri.
     */
    @Test fun `emergency override approves immediately and leaves an audit trail`() {
        val fixture = fixture(emergencyAllowed = true)
        val approval = request(fixture.service, 100, emergencyReason = "gardu mati, ONT harus diganti malam ini")

        assertThat(approval.status).isEqualTo(InventoryApprovalStatus.APPROVED)
        assertThat(fixture.audit.emergencyOverrides(tenant)).singleElement().satisfies({
            assertThat(it.approvalId).isEqualTo(approval.approvalId)
            assertThat(it.bypassedTiers).containsExactly(1, 2)
            assertThat(it.reason).contains("gardu mati")
        })
    }

    private class MutableTestClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneOffset.UTC
    }
}
