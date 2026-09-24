package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.InventoryWarehouseScopeApi
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class WarehouseReportService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val sites: SiteReferenceApi, private val reports: WarehouseReportPersistence, private val queries: WarehouseQueryPersistence,
    private val documents: WarehouseReportDocuments) {
    private val mapper = jacksonObjectMapper()

    @Transactional(timeout = 20)
    fun report(path: String, parameters: Map<String, List<String>>, csv: Boolean = false): String {
        val kind = WarehouseReportKind.parse(path)
        var filter = WarehouseQueryFilter.parse(parameters, kind.history)
        if (csv) {
            if ("page" in parameters || "size" in parameters) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            filter = filter.copy(page = 0, size = WarehouseReportCsv.MAX_ROWS)
        }
        val access = access()
        if (kind == WarehouseReportKind.WORK_ORDER_COSTS && !access.cost) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val body = if (kind == WarehouseReportKind.STOCK) queries.stock(filter, access) else reports.report(kind, filter, access)
        return if (csv) WarehouseReportCsv.render(mapper.readTree(body)) else body
    }

    @Transactional(timeout = 20)
    fun serialChain(asset: UUID, parameters: Map<String, List<String>>): String =
        reports.serialChain(asset, WarehouseQueryFilter.parse(parameters, true), access())

    @Transactional(timeout = 20)
    fun print(id: UUID, revision: Long): String {
        if (revision < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return documents.print(id, revision, access())
    }

    private fun access(): WarehouseQueryAccess {
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.report.view")
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence),
            areas, sites.visibleAreas(areas), current.platformAdmin || "inventory.cost.view" in current.permissions,
            current.platformAdmin || "inventory.provenance.view" in current.permissions)
    }
}
