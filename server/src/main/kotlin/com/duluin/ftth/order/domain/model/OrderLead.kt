package com.duluin.ftth.order.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/** Dari mana prospek ini masuk — menentukan seberapa dipercaya datanya dan siapa yang menindaklanjuti. */
enum class LeadSource { PUBLIC_WEB, CSV_IMPORT, OPERATOR, REFERRAL, OTHER }

/**
 * Perjalanan prospek. [CONVERTED] dan [DROPPED] sama-sama ujung, tapi ujungnya berbeda sifat:
 * CONVERTED tak bisa dibuka lagi (orangnya sudah jadi pelanggan — membukanya kembali akan
 * melahirkan identitas kedua untuk orang yang sama), sedangkan DROPPED boleh dihidupkan lagi
 * karena prospek yang dulu menolak sering kembali bertanya beberapa bulan kemudian.
 */
enum class LeadStatus { NEW, CONTACTED, QUALIFIED, CONVERTED, DROPPED }

/**
 * Calon pelanggan — orang yang sudah memesan tapi BELUM jadi pelanggan.
 *
 * Keberadaannya (keputusan K5 di `docs/rencana-gudang-pesanan.md`) menjawab satu masalah
 * konkret: sebelum ini `order_record.customer_id` NOT NULL memaksa setiap pemesan didaftarkan
 * lebih dulu sebagai `customer`. Prospek yang tak pernah jadi pelanggan lalu ikut terhitung di
 * tagihan, langganan, dan laporan churn — angka yang bohong sejak hari pertama. Prospek hidup
 * di sini sampai benar-benar dipromosikan lewat [convert].
 */
@Suppress("LongParameterList")
class OrderLead private constructor(
    val id: UUID,
    val tenantId: UUID,
    name: String,
    phone: String,
    email: String?,
    address: String?,
    latitude: Double?,
    longitude: Double?,
    interestedPlanId: UUID?,
    val source: LeadSource,
    status: LeadStatus,
    convertedCustomerId: UUID?,
    notes: String?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var name: String = name
        private set
    var phone: String = phone
        private set
    var email: String? = email
        private set
    var address: String? = address
        private set
    var latitude: Double? = latitude
        private set
    var longitude: Double? = longitude
        private set
    var interestedPlanId: UUID? = interestedPlanId
        private set
    var status: LeadStatus = status
        private set
    var convertedCustomerId: UUID? = convertedCustomerId
        private set
    var notes: String? = notes
        private set
    var updatedAt: Instant = updatedAt
        private set

    companion object {
        private const val MAX_NAME = 150
        private const val MAX_NOTES = 1000
        private const val MIN_PHONE_DIGITS = 8
        private const val MAX_PHONE_DIGITS = 15

        fun create(
            tenantId: UUID,
            name: String,
            phone: String,
            email: String? = null,
            address: String? = null,
            latitude: Double? = null,
            longitude: Double? = null,
            interestedPlanId: UUID? = null,
            source: LeadSource = LeadSource.OPERATOR,
            notes: String? = null,
            now: Instant = Instant.now(),
        ): OrderLead {
            validateCoordinate(latitude, longitude)
            return OrderLead(
                id = UuidV7.generate(),
                tenantId = tenantId,
                name = validName(name),
                phone = normalizePhone(phone),
                email = validEmail(email),
                address = address?.trim()?.ifBlank { null },
                latitude = latitude,
                longitude = longitude,
                interestedPlanId = interestedPlanId,
                source = source,
                status = LeadStatus.NEW,
                convertedCustomerId = null,
                notes = validNotes(notes),
                createdAt = now,
                updatedAt = now,
            )
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            phone: String,
            email: String?,
            address: String?,
            latitude: Double?,
            longitude: Double?,
            interestedPlanId: UUID?,
            source: LeadSource,
            status: LeadStatus,
            convertedCustomerId: UUID?,
            notes: String?,
            createdAt: Instant,
            updatedAt: Instant,
        ) = OrderLead(
            id, tenantId, name, phone, email, address, latitude, longitude, interestedPlanId,
            source, status, convertedCustomerId, notes, createdAt, updatedAt,
        )

        private fun validName(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) throw ValidationException("Nama calon pelanggan wajib diisi")
            if (trimmed.length > MAX_NAME) throw ValidationException("Nama calon pelanggan maksimal $MAX_NAME karakter")
            return trimmed
        }

        /**
         * Nomor HP WAJIB dan dinormalkan: ia satu-satunya kunci yang dipegang calon pelanggan
         * untuk melacak pesanannya, dan satu-satunya jalan operator menelepon balik. Spasi,
         * tanda hubung, titik, dan tanda kurung dibuang supaya "0812-3456-7890" dan
         * "081234567890" tidak menjadi dua prospek berbeda saat operator mencari.
         */
        private fun normalizePhone(value: String): String {
            val compact = value.filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' || it == '.' }
            val digits = compact.count { it.isDigit() }
            val shaped = compact.isNotEmpty() &&
                (compact.first().isDigit() || compact.first() == '+') &&
                compact.drop(1).all { it.isDigit() }
            if (!shaped || digits !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) {
                throw ValidationException("Nomor HP calon pelanggan tidak valid")
            }
            return compact
        }

        private fun validEmail(value: String?): String? {
            val trimmed = value?.trim()?.ifBlank { null } ?: return null
            if (!trimmed.contains('@') || trimmed.startsWith('@') || trimmed.endsWith('@')) {
                throw ValidationException("Email calon pelanggan tidak valid")
            }
            return trimmed
        }

        private fun validNotes(value: String?): String? {
            val trimmed = value?.trim()?.ifBlank { null } ?: return null
            if (trimmed.length > MAX_NOTES) throw ValidationException("Catatan maksimal $MAX_NOTES karakter")
            return trimmed
        }

        private fun validateCoordinate(latitude: Double?, longitude: Double?) {
            if ((latitude == null) != (longitude == null)) throw ValidationException("Koordinat harus berpasangan")
            if (latitude != null && longitude != null && (latitude !in -90.0..90.0 || longitude !in -180.0..180.0)) {
                throw ValidationException("Koordinat calon pelanggan tidak valid")
            }
        }
    }

    /** Perubahan biodata. Field null = "pertahankan yang ada", supaya PATCH parsial tak mengosongkan data. */
    fun updateProfile(
        name: String? = null,
        phone: String? = null,
        email: String? = null,
        address: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        interestedPlanId: UUID? = null,
        notes: String? = null,
        now: Instant = Instant.now(),
    ) {
        requireEditable()
        if (latitude != null || longitude != null) {
            val lat = latitude ?: this.latitude
            val lon = longitude ?: this.longitude
            validateCoordinate(lat, lon)
            this.latitude = lat
            this.longitude = lon
        }
        name?.let { this.name = validName(it) }
        phone?.let { this.phone = normalizePhone(it) }
        email?.let { this.email = validEmail(it) }
        address?.let { this.address = it.trim().ifBlank { null } }
        notes?.let { this.notes = validNotes(it) }
        interestedPlanId?.let { this.interestedPlanId = it }
        this.updatedAt = now
    }

    /**
     * Transisi status manual oleh operator. [LeadStatus.CONVERTED] SENGAJA tidak bisa dicapai
     * lewat sini — konversi punya efek samping (pelanggan baru lahir) dan hanya boleh terjadi
     * lewat [convert] di dalam satu transaksi bersama pembuatan pelanggannya. Kalau boleh,
     * operator bisa menandai lead CONVERTED tanpa pelanggannya pernah ada, dan pesanan itu
     * hilang dari dua antrean sekaligus.
     */
    fun changeStatus(target: LeadStatus, now: Instant = Instant.now()) {
        if (target == LeadStatus.CONVERTED) {
            throw ValidationException("Status CONVERTED hanya boleh lewat promosi calon pelanggan")
        }
        if (status == LeadStatus.CONVERTED) {
            throw ConflictException("Calon pelanggan sudah menjadi pelanggan dan tidak bisa diubah lagi")
        }
        if (target == status) return
        val allowed = when (status) {
            LeadStatus.NEW -> setOf(LeadStatus.CONTACTED, LeadStatus.QUALIFIED, LeadStatus.DROPPED)
            LeadStatus.CONTACTED -> setOf(LeadStatus.QUALIFIED, LeadStatus.DROPPED)
            LeadStatus.QUALIFIED -> setOf(LeadStatus.CONTACTED, LeadStatus.DROPPED)
            // Prospek yang dulu menolak dihidupkan sebagai "sudah pernah dihubungi", bukan
            // dikembalikan ke NEW yang membuatnya tampak belum pernah disentuh siapa pun.
            LeadStatus.DROPPED -> setOf(LeadStatus.CONTACTED)
            LeadStatus.CONVERTED -> emptySet()
        }
        if (target !in allowed) throw ConflictException("Transisi ${status.name} ke ${target.name} tidak diizinkan")
        status = target
        updatedAt = now
    }

    /**
     * Promosi jadi pelanggan. Idempoten menurut [customerId]: mengulang promosi yang sama
     * (mis. klien kehilangan respons lalu menekan tombol lagi) tak melempar dan tak mengubah
     * apa pun. Promosi ke pelanggan BERBEDA ditolak — itu tanda dua pelanggan sudah terlanjur
     * dibuat untuk satu orang, dan menimpanya diam-diam akan menyembunyikan duplikat itu.
     */
    fun convert(customerId: UUID, now: Instant = Instant.now()) {
        if (status == LeadStatus.CONVERTED) {
            if (convertedCustomerId != customerId) {
                throw ConflictException("Calon pelanggan sudah dipromosikan ke pelanggan lain")
            }
            return
        }
        if (status == LeadStatus.DROPPED) {
            throw ConflictException("Calon pelanggan sudah dibatalkan; hidupkan dulu sebelum dipromosikan")
        }
        status = LeadStatus.CONVERTED
        convertedCustomerId = customerId
        updatedAt = now
    }

    private fun requireEditable() {
        if (status == LeadStatus.CONVERTED) {
            throw ConflictException("Calon pelanggan sudah menjadi pelanggan; ubah datanya di modul pelanggan")
        }
    }
}
