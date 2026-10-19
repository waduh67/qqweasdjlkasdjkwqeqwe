package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class WorkCalendarScheduler(private val tenants: TenantApi, private val source: HolidaySource,
    private val calendar: WorkCalendarService, private val clock: B2BOperationalClock) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Scheduled(cron = "0 10 0 1 1 *", zone = "Asia/Jakarta")
    fun refresh() {
        val year = clock.today().year
        val imported = runCatching { source.fetch(year) }.getOrElse {
            log.warn("Sinkronisasi kalender {} gagal: {}", year, it.message); return
        }
        tenants.findActiveTenantIds().filter { it != tenants.platformTenantId() }.forEach { tenant ->
            runCatching { TenantContext.runAs(tenant) { calendar.refresh(year, imported) } }
                .onFailure { log.warn("Penyimpanan kalender tenant {} tahun {} gagal: {}", tenant, year, it.message) }
        }
    }
}
