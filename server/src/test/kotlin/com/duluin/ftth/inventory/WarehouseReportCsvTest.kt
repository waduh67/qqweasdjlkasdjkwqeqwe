package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.WarehouseReportCsv
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReportCsvTest {
    @Test fun `spreadsheet formula prefixes remain text after whitespace and controls are trimmed`() {
        for (prefix in listOf("=", "+", "-", "@", " =", "\t=", "\r=", "\n=", "\uFEFF=", "\u0000=", "\u2003=")) {
            val formula = prefix + "1+1"
            assertThat(WarehouseReportCsv.cell(formula)).isEqualTo("\"'$formula\"")
        }
        assertThat(WarehouseReportCsv.cell("a,\"b\"")).isEqualTo("\"a,\"\"b\"\"\"")
        assertThat(WarehouseReportCsv.cell("1000")).isEqualTo("\"1000\"")
    }
}
