package com.karuhun.ftth.inventory.adapter.outbound.persistence

import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.network.SiteUsageProbe
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class InventorySiteUsageProbe(private val jdbc: WarehouseCommandJdbc) : SiteUsageProbe {
    override fun assertSiteUnreferenced(siteId: UUID) = jdbc.execute { sql ->
        if (sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND site_id=? LIMIT 1", sql.tenant, siteId) != null) {
            throw ConflictException("Site masih digunakan lokasi gudang; pindahkan referensi lokasi sebelum mengubah area atau menghapus site")
        }
    }
}
