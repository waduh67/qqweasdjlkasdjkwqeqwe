package com.duluin.ftth.network.adapter.outbound.persistence

import org.springframework.data.jpa.domain.Specification
import java.util.UUID

/**
 * Filter dinamis lewat Criteria API, bukan JPQL dengan parameter yang boleh null.
 *
 * Alasannya konkret: parameter null di JPQL (`:odcId is null or ...`) dikirim ke
 * Postgres tanpa tipe, dan Postgres menolaknya dengan galat yang menyesatkan
 * seperti `function lower(bytea) does not exist`. Predikat yang dirakit di sini
 * hanya muncul di SQL kalau filternya memang dipakai — tidak ada parameter null
 * yang perlu ditebak tipenya.
 *
 * Batas `T : Any` wajib: tanpa itu Kotlin menyimpulkan `T` yang boleh null,
 * sedangkan `Specification<T>` menuntut tipe entity yang non-null.
 */
internal object NetworkSpecifications {

    /** Pencarian teks bebas pada kolom `code` dan `name`. String kosong = tanpa filter. */
    fun <T : Any> textMatches(query: String): Specification<T> = Specification { root, _, cb ->
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            cb.conjunction()
        } else {
            val pattern = "%$needle%"
            cb.or(
                cb.like(cb.lower(root.get("code")), pattern),
                cb.like(cb.lower(root.get("name")), pattern),
            )
        }
    }

    /**
     * Pembatasan area sesuai dimensi SCOPE pada RBAC.
     *
     * `null` = tanpa batas. Set KOSONG sengaja menghasilkan predikat yang selalu
     * salah, bukan "tanpa filter": pengguna yang dibatasi area tapi belum diberi
     * area satu pun harus melihat nol data, bukan seluruh tenant.
     */
    fun <T : Any> withinAreas(areaIds: Set<UUID>?): Specification<T> = Specification { root, _, cb ->
        when {
            areaIds == null -> cb.conjunction()
            areaIds.isEmpty() -> cb.disjunction()
            else -> root.get<UUID?>("areaId").`in`(areaIds)
        }
    }

    /** Kesetaraan opsional: filter hanya ikut kalau nilainya ada. */
    fun <T : Any> equals(attribute: String, value: Any?): Specification<T> = Specification { root, _, cb ->
        if (value == null) cb.conjunction() else cb.equal(root.get<Any>(attribute), value)
    }

    /** Kabel menyentuh sebuah simpul bila simpul itu ada di ujung mana pun. */
    fun endpointIs(kind: Any, id: UUID): Specification<CableJpaEntity> = Specification { root, _, cb ->
        cb.or(
            cb.and(cb.equal(root.get<Any>("fromKind"), kind), cb.equal(root.get<UUID>("fromId"), id)),
            cb.and(cb.equal(root.get<Any>("toKind"), kind), cb.equal(root.get<UUID>("toId"), id)),
        )
    }

    /** Kabel yang salah satu ujung id-nya ada di kumpulan simpul. */
    fun endpointInNodes(nodeIds: Set<UUID>): Specification<CableJpaEntity> = Specification { root, _, cb ->
        cb.or(root.get<UUID>("fromId").`in`(nodeIds), root.get<UUID>("toId").`in`(nodeIds))
    }
}
