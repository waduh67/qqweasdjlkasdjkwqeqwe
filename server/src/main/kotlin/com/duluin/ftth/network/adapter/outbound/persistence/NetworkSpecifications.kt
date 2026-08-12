package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.network.domain.model.CableAttachmentRole
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
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

    /**
     * Kabel BERUJUNG di sebuah simpul — bukan sekadar menyinggahinya.
     *
     * Peran END sengaja ikut disaring: sejak V99 sebuah kabel juga mencatat
     * simpul yang cuma dikupas atau dilewatinya di tengah bentang, dan yang
     * bertanya di sini (okupansi port, penelusuran hulu-hilir, penempatan OTDR)
     * menanyakan ujung dalam arti harfiah. Tanpa saringan itu ODP ke-3 pada
     * selubung yang lewat akan mengaku sebagai ujung kabel.
     */
    fun endpointIs(kind: Any, id: UUID): Specification<CableJpaEntity> = Specification { root, query, cb ->
        endsAt(root, query, cb) { att ->
            cb.and(cb.equal(att.get<Any>("nodeKind"), kind), cb.equal(att.get<UUID>("nodeId"), id))
        }
    }

    /** Versi banyak-simpul: kabel yang salah satu UJUNG-nya ada di kumpulan simpul. */
    fun endpointInNodes(nodeIds: Set<UUID>): Specification<CableJpaEntity> = Specification { root, query, cb ->
        endsAt(root, query, cb) { att -> att.get<UUID>("nodeId").`in`(nodeIds) }
    }

    /**
     * `exists (select 1 from cable_attachment ...)`, bukan join: sebuah kabel
     * punya banyak singgahan, dan join akan menggandakan barisnya sehingga
     * halaman pencarian melaporkan kabel yang sama berkali-kali.
     */
    private fun endsAt(
        root: Root<CableJpaEntity>,
        query: CriteriaQuery<*>?,
        cb: CriteriaBuilder,
        node: (Root<CableAttachmentJpaEntity>) -> Predicate,
    ): Predicate {
        val sub = requireNotNull(query) { "endpoint filter butuh CriteriaQuery" }.subquery(UUID::class.java)
        val att = sub.from(CableAttachmentJpaEntity::class.java)
        sub.select(att.get("cableId"))
        sub.where(
            cb.equal(att.get<UUID>("cableId"), root.get<UUID>("id")),
            cb.equal(att.get<Any>("role"), CableAttachmentRole.END),
            node(att),
        )
        return cb.exists(sub)
    }
}
