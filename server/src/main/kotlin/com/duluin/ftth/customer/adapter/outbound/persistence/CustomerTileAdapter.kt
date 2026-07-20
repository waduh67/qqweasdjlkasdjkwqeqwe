package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.customer.application.port.outbound.CustomerTileRenderer
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Layer pelanggan untuk peta. Sama seperti sisi network, wajib lewat
 * [EntityManager] agar RLS mendapat GUC tenant-nya.
 *
 * Layer ini membawa `odp_id` supaya klien bisa menggambar garis terang antara
 * pelanggan dan ODP-nya tanpa perlu bertanya balik ke server per titik.
 */
@Component
class CustomerTileAdapter : CustomerTileRenderer {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun render(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray {
        if (areaIds != null && areaIds.isEmpty()) return ByteArray(0)

        val scoped = areaIds != null
        val areaFilter = if (scoped) "AND c.area_id::text = ANY(string_to_array(:areaIds, ','))" else ""
        val sql = """
            WITH env AS (
                SELECT ST_TileEnvelope(:z, :x, :y) AS mercator,
                       ST_Transform(ST_TileEnvelope(:z, :x, :y), 4326) AS wgs84
            )
            SELECT COALESCE((
                SELECT ST_AsMVT(t, 'customer', 4096, 'geom') FROM (
                    SELECT c.id::text AS id, c.code AS code, c.name AS name, c.status AS status,
                           o.odp_id::text AS odp_id, o.status AS onu_status,
                           ST_AsMVTGeom(ST_Transform(c.location, 3857), env.mercator, 4096, 64, true) AS geom
                    FROM customer c
                    CROSS JOIN env
                    LEFT JOIN onu o ON o.customer_id = c.id
                    WHERE c.location && env.wgs84 $areaFilter
                ) t WHERE t.geom IS NOT NULL
            ), ''::bytea)
            FROM env
        """.trimIndent()

        val query = entityManager.createNativeQuery(sql)
            .setParameter("z", z)
            .setParameter("x", x)
            .setParameter("y", y)
        if (scoped) {
            query.setParameter("areaIds", areaIds!!.joinToString(",") { it.toString() })
        }
        return query.singleResult as? ByteArray ?: ByteArray(0)
    }
}
