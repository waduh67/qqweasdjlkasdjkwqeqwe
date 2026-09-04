package com.duluin.ftth.onboarding

import com.duluin.ftth.onboarding.application.service.CustomerImportPromotionPort
import com.duluin.ftth.onboarding.application.service.CustomerImportPromotionScheduler
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerImportPromotionSchedulerTest {
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    @AfterEach
    fun clearTenant() = TenantContext.clear()

    @Test
    fun `each tenant is installed before worker and failure does not stop next tenant`() {
        val observed = mutableListOf<UUID?>()
        val tenants = RecordingTenantApi(listOf(first, second)) { assertThat(TenantContext.tenantIdOrNull()).isNull() }
        val worker = object : CustomerImportPromotionPort {
            override fun promoteOne() {
                observed += TenantContext.tenantIdOrNull()
                if (observed.size == 1) error("first tenant failure")
            }
        }

        CustomerImportPromotionScheduler(tenants, worker).promoteOne()

        assertThat(observed).containsExactly(first, second)
        assertThat(TenantContext.tenantIdOrNull()).isNull()
    }

    private class RecordingTenantApi(
        private val ids: List<UUID>,
        private val beforeLookup: () -> Unit,
    ) : TenantApi {
        override fun findActiveTenantIds(): List<UUID> { beforeLookup(); return ids }
        override fun findById(id: UUID): TenantRef? = null
        override fun findBySlug(slug: String): TenantRef? = null
        override fun requireById(id: UUID): TenantRef = error("unused")
        override fun platformTenantId(): UUID = error("unused")
        override fun ensureTenant(slug: String, name: String): TenantRef = error("unused")
        override fun suspend(id: UUID): TenantRef = error("unused")
        override fun activate(id: UUID): TenantRef = error("unused")
    }
}
