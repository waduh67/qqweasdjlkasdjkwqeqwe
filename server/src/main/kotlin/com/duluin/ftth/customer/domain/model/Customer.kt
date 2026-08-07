package com.duluin.ftth.customer.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class CustomerStatus {
    /** Calon pelanggan: sudah didata & dipetakan, layanan belum terpasang. */
    PROSPECT,
    ACTIVE,
    /** Diisolir (mis. tunggakan) — perangkat tetap terpasang. */
    SUSPENDED,
    TERMINATED,
}

/**
 * Pelanggan beserta titik lokasi rumahnya. Lokasi bersifat wajib karena inilah
 * yang membuat pertanyaan operasional seperti "siapa saja yang terdampak kalau
 * ODP ini mati" bisa dijawab di peta, bukan cuma di tabel.
 */
class Customer private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    name: String,
    phone: String?,
    email: String?,
    address: String,
    location: Coordinate,
    areaId: UUID?,
    idCardNumber: String?,
    status: CustomerStatus,
) {
    var name: String = name
        private set

    var phone: String? = phone
        private set

    var email: String? = email
        private set

    var address: String = address
        private set

    var location: Coordinate = location
        private set

    var areaId: UUID? = areaId
        private set

    /** Nomor identitas (NIK/KTP/paspor) — opsional; berguna untuk KYC & pelaporan regulasi ISP. */
    var idCardNumber: String? = idCardNumber
        private set

    var status: CustomerStatus = status
        private set

    fun update(
        name: String,
        phone: String?,
        email: String?,
        address: String,
        location: Coordinate,
        areaId: UUID?,
        idCardNumber: String?,
    ) {
        this.name = validateName(name)
        this.phone = normalizePhone(phone)
        this.email = email?.trim()?.takeIf { it.isNotEmpty() }
        this.address = validateAddress(address)
        this.location = location
        this.areaId = areaId
        this.idCardNumber = normalizeIdCard(idCardNumber)
    }

    fun changeStatus(status: CustomerStatus) {
        this.status = status
    }

    companion object {
        private val CODE_PATTERN = Regex("^[A-Z0-9][A-Z0-9._/-]{1,39}$")

        /**
         * Format kode pelanggan yang dibuat otomatis ketika operator tak mengetiknya sendiri —
         * `CUST-{yyyyMMdd}-{acak}` (mis. `CUST-20260807-K7M2Q9`). Tanggal menjaga keterbacaan &
         * urutan kasar, sufiks acak menjamin keunikan tanpa kueri urutan global yang rapuh di DB
         * bersama. Keunikan akhir tetap dijaga UNIQUE(tenant, code); pemanggil mengulang bila bentrok.
         */
        const val AUTO_CODE_PREFIX = "CUST-"
        private val AUTO_CODE_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        fun formatAutoCode(date: LocalDate, randomSuffix: String): String =
            AUTO_CODE_PREFIX + date.format(AUTO_CODE_DATE) + "-" + randomSuffix

        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            phone: String?,
            email: String?,
            address: String,
            location: Coordinate,
            areaId: UUID?,
            idCardNumber: String? = null,
            status: CustomerStatus = CustomerStatus.PROSPECT,
        ): Customer = Customer(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = validateCode(code),
            name = validateName(name),
            phone = normalizePhone(phone),
            email = email?.trim()?.takeIf { it.isNotEmpty() },
            address = validateAddress(address),
            location = location,
            areaId = areaId,
            idCardNumber = normalizeIdCard(idCardNumber),
            status = status,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            name: String,
            phone: String?,
            email: String?,
            address: String,
            location: Coordinate,
            areaId: UUID?,
            idCardNumber: String?,
            status: CustomerStatus,
        ): Customer = Customer(id, tenantId, code, name, phone, email, address, location, areaId, idCardNumber, status)

        private fun validateCode(code: String): String {
            val normalized = code.trim().uppercase()
            if (!CODE_PATTERN.matches(normalized)) {
                throw ValidationException("Kode pelanggan '$code' tidak valid: 2-40 karakter alfanumerik")
            }
            return normalized
        }

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..150) throw ValidationException("Nama pelanggan harus 2-150 karakter")
            return trimmed
        }

        private fun validateAddress(address: String): String {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) throw ValidationException("Alamat pelanggan wajib diisi")
            if (trimmed.length > 500) throw ValidationException("Alamat maksimal 500 karakter")
            return trimmed
        }

        /**
         * Menyeragamkan nomor telepon ke format digit saja (plus opsional).
         * Penting karena nomor inilah yang dipakai broadcast gangguan di Phase 3 —
         * spasi dan tanda hubung yang tidak konsisten membuat gateway menolaknya.
         */
        private fun normalizePhone(phone: String?): String? {
            val raw = phone?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val normalized = raw.replace(Regex("[\\s().-]"), "")
            if (!Regex("^\\+?\\d{6,20}$").matches(normalized)) {
                throw ValidationException("Nomor telepon '$phone' tidak valid")
            }
            return normalized
        }

        /**
         * Menormalkan nomor identitas: kosong → null. Sengaja longgar (tak memaksa 16 digit NIK)
         * agar paspor/KITAS pun bisa disimpan; hanya batasi panjang agar tak jadi teks bebas.
         */
        private fun normalizeIdCard(idCardNumber: String?): String? {
            val trimmed = idCardNumber?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 32) throw ValidationException("Nomor identitas maksimal 32 karakter")
            return trimmed
        }
    }
}
