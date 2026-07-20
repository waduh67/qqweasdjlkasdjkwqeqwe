package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.network.application.port.outbound.NetworkTileRenderer
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Vector tile aset jaringan, dirender penuh di dalam Postgres.
 *
 * PENTING — query dijalankan lewat [EntityManager], BUKAN `JdbcTemplate`.
 * GUC `app.tenant_id` yang mengaktifkan Row-Level Security hanya dipasang pada
 * connection yang dipinjam Hibernate (lihat `TenantConnectionProvider`).
 * Connection yang diambil langsung dari pool tidak punya GUC itu, sehingga RLS
 * menolak semua baris dan peta akan kosong tanpa penjelasan.
 */
@Component
class NetworkTileAdapter : NetworkTileRenderer {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun render(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray {
        // Pengguna yang dibatasi area tapi belum diberi area mana pun tidak boleh
        // melihat apa pun — dan tidak perlu merepotkan database untuk itu.
        if (areaIds != null && areaIds.isEmpty()) return ByteArray(0)

        val scoped = areaIds != null
        val query = entityManager.createNativeQuery(buildSql(scoped))
            .setParameter("z", z)
            .setParameter("x", x)
            .setParameter("y", y)
        if (scoped) {
            query.setParameter("areaIds", areaIds!!.joinToString(",") { it.toString() })
        }
        return query.singleResult as? ByteArray ?: ByteArray(0)
    }

    /**
     * Menggabungkan empat layer menjadi satu tile. Rangkaian byte MVT boleh
     * disambung begitu saja: `layers` adalah repeated field protobuf, sehingga
     * hasil sambungan tetap tile yang sah berisi seluruh layer.
     *
     * Perbandingan bbox dilakukan pada geometri 4326 (`&& :envelope4326`) agar
     * indeks GiST terpakai; transformasi ke 3857 hanya untuk baris yang lolos.
     */
    private fun buildSql(scoped: Boolean): String {
        // Fragmen tetap, bukan hasil interpolasi input pengguna — daftar area
        // tetap dikirim sebagai parameter terikat.
        val areaFilter = if (scoped) "AND a.area_id::text = ANY(string_to_array(:areaIds, ','))" else ""
        return """
            WITH env AS (
                SELECT ST_TileEnvelope(:z, :x, :y) AS mercator,
                       ST_Transform(ST_TileEnvelope(:z, :x, :y), 4326) AS wgs84
            )
            SELECT
                COALESCE((
                    SELECT ST_AsMVT(t, 'site', 4096, 'geom') FROM (
                        SELECT a.id::text AS id, a.code AS code, a.name AS name,
                               ST_AsMVTGeom(ST_Transform(a.location, 3857), env.mercator, 4096, 64, true) AS geom
                        FROM site a CROSS JOIN env
                        WHERE a.location && env.wgs84 $areaFilter
                    ) t WHERE t.geom IS NOT NULL
                ), ''::bytea)
                ||
                COALESCE((
                    SELECT ST_AsMVT(t, 'odc', 4096, 'geom') FROM (
                        SELECT a.id::text AS id, a.code AS code, a.name AS name,
                               a.capacity AS capacity, a.status AS status,
                               a.splitter_ratio AS splitter_ratio,
                               ST_AsMVTGeom(ST_Transform(a.location, 3857), env.mercator, 4096, 64, true) AS geom
                        FROM odc a CROSS JOIN env
                        WHERE a.location && env.wgs84 $areaFilter
                    ) t WHERE t.geom IS NOT NULL
                ), ''::bytea)
                ||
                COALESCE((
                    SELECT ST_AsMVT(t, 'odp', 4096, 'geom') FROM (
                        SELECT a.id::text AS id, a.code AS code, a.name AS name,
                               a.capacity AS capacity, a.status AS status,
                               a.splitter_ratio AS splitter_ratio, a.odc_id::text AS odc_id,
                               ST_AsMVTGeom(ST_Transform(a.location, 3857), env.mercator, 4096, 64, true) AS geom
                        FROM odp a CROSS JOIN env
                        WHERE a.location && env.wgs84 $areaFilter
                    ) t WHERE t.geom IS NOT NULL
                ), ''::bytea)
                ||
                COALESCE((
                    SELECT ST_AsMVT(t, 'cable', 4096, 'geom') FROM (
                        SELECT c.id::text AS id, c.code AS code, c.name AS name,
                               c.cable_type AS cable_type, c.core_count AS core_count,
                               c.status AS status,
                               ST_AsMVTGeom(ST_Transform(c.route, 3857), env.mercator, 4096, 64, true) AS geom
                        FROM cable c CROSS JOIN env
                        WHERE c.route && env.wgs84
                    ) t WHERE t.geom IS NOT NULL
                ), ''::bytea)
            FROM env
        """.trimIndent()
    }
}
