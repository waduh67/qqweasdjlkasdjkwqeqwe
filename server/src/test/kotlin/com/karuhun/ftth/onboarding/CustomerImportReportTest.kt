package com.karuhun.ftth.onboarding

import com.karuhun.ftth.onboarding.application.port.inbound.*
import com.karuhun.ftth.onboarding.application.service.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CustomerImportReportTest {
    @Test fun `report has one record per imported customer with source row numbers`() {
        val report = batch(listOf(CustomerImportOutcome("OLD-1", CustomerImportStatus.CREATED, null), CustomerImportOutcome("OLD-2", CustomerImportStatus.FAILED, "Paket tidak ditemukan"))).toSafeCsv()
        assertThat(report).isEqualTo("row,column,code,message\r\n2,OLD-1,CREATED,\r\n3,OLD-2,FAILED,Paket tidak ditemukan\r\n")
    }
    @Test fun `formula prefixes are protected before CSV escaping`() {
        val report = batch(listOf(CustomerImportOutcome(" =SUM(1,2)", CustomerImportStatus.FAILED, "Nama \"lama\""))).toSafeCsv()
        assertThat(report).contains("\"' =SUM(1,2)\"", "\"Nama \"\"lama\"\"\"")
    }
    private fun batch(rows: List<CustomerImportOutcome>) = CustomerImportBatchView(UUID.randomUUID(), "a".repeat(64), ImportMode.ALREADY_INSTALLED, CustomerImportBatchState.COMMITTED, emptyList(), ImportCustomersResult(0, 0, 0, 0, rows), Instant.now())
}
