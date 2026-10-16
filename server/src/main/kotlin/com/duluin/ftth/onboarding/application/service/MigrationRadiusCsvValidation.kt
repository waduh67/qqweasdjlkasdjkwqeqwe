package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportRow
import org.springframework.stereotype.Component

/** Validate the reviewed CSV before credentials are sealed for background promotion. */
@Component
class MigrationRadiusCsvValidation(private val bng: BngApi, private val catalog: CatalogApi) {
    fun validate(rows: List<CustomerImportRow>): List<CsvImportError> {
        val plans = rows.mapNotNull { it.packageName }.distinct().associateWith { name ->
            catalog.findPlanByName(name)?.let { catalog.findPlanNetwork(it.planId) }
        }
        val routers = rows.mapNotNull { it.routerName?.trim()?.takeIf(String::isNotEmpty) }.distinct()
            .associateWith { bng.resolveNasByName(it) }
        return buildList {
            rows.forEachIndexed { index, row ->
                fun issue(column: String, message: String) { add(CsvImportError(index + 2, column, "RADIUS_VALIDATION", message)) }
                val type = resolveImportAuthType(row.connectionType)
                if (row.mikrotikUsername.isNullOrBlank()) issue("username", "Isi username akun jaringan")
                if (type in setOf("PPPOE", "HOTSPOT") && row.mikrotikPassword.isNullOrEmpty()) issue("password", "Isi password akun jaringan")
                if (routers[row.routerName?.trim()] == null) issue("router_name", "Pilih nama BRAS yang terdaftar")
                val plan = plans[row.packageName]
                if (plan == null) issue("package_name", "Pilih paket yang terdaftar")
                else if (type == null || type !in plan.serviceTypes) issue("connection_type", "Jenis koneksi tidak didukung paket")
                if (type == "STATIC" && row.framedIp.isNullOrBlank()) issue("framed_ip", "Isi alamat IP untuk koneksi statis")
            }
        }
    }
}
