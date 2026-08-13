package com.duluin.ftth.monitoring.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import java.time.Instant
import java.util.UUID

/** Entitas yang bisa menjadi subjek alarm. */
enum class AlarmEntityType {
    ONU,
    OLT,
    ODP,
    ODC,
    COLLECTOR,

    /**
     * Pelanggan sebagai subjek — dipakai alarm yang lahir di ATAS lapisan optik, mis.
     * sesi PPPoE putus (BRAS/RADIUS). Berbeda dari [ONU] yang menyoal perangkat fisik
     * di rumah; [CUSTOMER] menyoal identitas jaringan pelanggan yang gis warnai ke
     * marker pelanggan + kabel drop-nya via customerId.
     */
    CUSTOMER,
}

enum class AlarmSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

enum class AlarmStatus {
    ACTIVE,
    ACKNOWLEDGED,
    CLEARED,
    ;

    val open: Boolean get() = this != CLEARED
}

/**
 * Jenis alarm yang dikenal sistem, beserta sifat bawaannya.
 *
 * Ambang bawaan diletakkan di sini — bukan di tabel konfigurasi kosong — supaya
 * tenant baru langsung punya pemantauan yang masuk akal tanpa harus menyetel
 * apa pun. Tenant tetap bisa menimpanya lewat `AlarmRule`.
 */
enum class AlarmKind(
    val entityType: AlarmEntityType,
    val defaultSeverity: AlarmSeverity,
    val defaultWarningThreshold: Double?,
    val defaultCriticalThreshold: Double?,
    val defaultSustainSeconds: Int,
    val description: String,
) {
    /** Loss of Signal: fiber putus atau konektor lepas. Paling mendesak. */
    ONU_LOS(AlarmEntityType.ONU, AlarmSeverity.CRITICAL, null, null, 0, "ONU kehilangan sinyal (LOS)"),

    /**
     * ONU tidak menjawab. Diberi jeda tahan 10 menit karena mati listrik di rumah
     * pelanggan terlihat persis sama dengan gangguan jaringan — dan sebagian besar
     * pulih sendiri.
     */
    ONU_OFFLINE(AlarmEntityType.ONU, AlarmSeverity.WARNING, null, null, 600, "ONU tidak terhubung"),

    /**
     * Redaman terima terlalu lemah. Ambang -25 dBm masih bisa dipakai tapi sudah
     * layak dilihat; -27 dBm sudah di ambang sensitivitas penerima GPON.
     */
    ONU_LOW_RX(AlarmEntityType.ONU, AlarmSeverity.WARNING, -25.0, -27.0, 300, "Redaman terima ONU lemah"),

    /** Terlalu terang — bisa merusak penerima, biasanya salah pasang splitter. */
    ONU_HIGH_RX(AlarmEntityType.ONU, AlarmSeverity.WARNING, -8.0, -5.0, 300, "Redaman terima ONU terlalu kuat"),

    /** OLT tidak bisa dihubungi collector — dampaknya seluruh pelanggan di bawahnya. */
    OLT_UNREACHABLE(AlarmEntityType.OLT, AlarmSeverity.CRITICAL, null, null, 0, "OLT tidak bisa dihubungi"),

    /**
     * Collector berhenti melapor. Bukan gangguan jaringan pelanggan, tapi wajib
     * terlihat: tanpa ini, collector yang mati tampak seperti "semua baik-baik
     * saja" karena tidak ada alarm baru yang masuk.
     */
    COLLECTOR_SILENT(AlarmEntityType.COLLECTOR, AlarmSeverity.CRITICAL, null, null, 900, "Collector berhenti melapor"),

    /**
     * Sesi PPPoE pelanggan putus di BRAS — pelanggan offline walau ONU-nya boleh jadi
     * masih menyala. Menutup celah "ONU hidup tapi pelanggan tak bisa internetan"
     * (mis. salah kredensial, di-suspend sepihak router, sesi ngadat) yang tak terlihat
     * di telemetri optik. WARNING, bukan CRITICAL: satu pelanggan offline bukan gangguan
     * masif, dan penghakiman "putus" sudah diredam ambang basi sesi di bng (poll BRAS
     * cuma melaporkan sesi hidup; sesi berakhir menghilang tanpa ditandai), jadi tak
     * perlu jeda tahan tambahan di sini.
     */
    PPPOE_DOWN(AlarmEntityType.CUSTOMER, AlarmSeverity.WARNING, null, null, 0, "Sesi PPPoE pelanggan putus"),
    ;

    /**
     * Ke arah mana angka memburuk — dan `null` untuk jenis yang tak berambang sama
     * sekali (terjadi atau tidak, seperti LOS).
     *
     * Ini SATU-SATUNYA tempat arah itu ditetapkan: `AlarmRule.evaluate` memakainya
     * untuk membandingkan, pemeriksa setelan memakainya untuk menolak ambang yang
     * terbalik, dan layar setelan memakainya untuk menulis "alarm saat ≥ ambang".
     * Dulu ketiganya menyimpan pengetahuan yang sama sendiri-sendiri — cukup satu
     * yang lupa diperbarui untuk membuat layar menjanjikan hal yang tak dikerjakan
     * mesinnya.
     */
    val thresholdDirection: AlarmThresholdDirection?
        get() = when (this) {
            ONU_LOW_RX -> AlarmThresholdDirection.LOWER_IS_WORSE
            ONU_HIGH_RX -> AlarmThresholdDirection.HIGHER_IS_WORSE
            else -> null
        }

    /** Satuan angka ambangnya, untuk ditempel di sebelah kolom isian. */
    val thresholdUnit: String?
        get() = thresholdDirection?.let { "dBm" }

    /**
     * Batas fisik perangkat yang menentukan ambangnya — dipajang di layar setelan.
     *
     * Sengaja satu-dua kalimat fakta, bukan paragraf penjelasan: yang membacanya
     * sedang menyetel angka, bukan sedang belajar GPON. Yang wajib ada cuma angka
     * batas perangkat dan tindakan yang benar; sisanya mengganggu.
     */
    val guidance: String
        get() = when (this) {
            ONU_LOS -> "Tak ada cahaya sama sekali: serat putus, konektor lepas, atau ONU mati."
            ONU_OFFLINE -> "ONU tak menjawab. Mati listrik pelanggan terlihat sama dengan gangguan jaringan."
            ONU_LOW_RX ->
                "Penerima B+/C+ menyerah di −27…−28 dBm. Di −25 dBm tak ada cadangan untuk hujan " +
                    "atau sambungan baru."
            ONU_HIGH_RX ->
                "Penerima B+/C+ jenuh di atas −8 dBm: paket hilang walau ONU online. Obatnya " +
                    "atenuator, bukan menggeser ambang."
            OLT_UNREACHABLE -> "Collector tak bisa menghubungi OLT. Periksa jalur manajemen, bukan cuma serat."
            COLLECTOR_SILENT -> "Collector berhenti melapor — pemantauan buta tampak seperti jaringan tenang."
            PPPOE_DOWN ->
                "Sesi putus di BRAS walau ONU boleh jadi menyala: salah kredensial, suspend, atau " +
                    "sesi ngadat."
        }
}

/**
 * Arah keburukan sebuah ambang. Redaman punya dua sisi yang sama-sama gawat, dan
 * keduanya diukur dengan satuan yang sama; tanpa penanda arah, "−27" dan "−5"
 * tampak seperti soal yang sama padahal berlawanan.
 */
enum class AlarmThresholdDirection {
    /** Makin KECIL makin buruk (redaman lemah): alarm saat nilai ≤ ambang. */
    LOWER_IS_WORSE,

    /** Makin BESAR makin buruk (redaman terlalu kuat): alarm saat nilai ≥ ambang. */
    HIGHER_IS_WORSE,
}

/**
 * Satu kondisi abnormal yang sedang atau pernah terjadi.
 *
 * Alarm bersifat berumur panjang, bukan kejadian sesaat: siklus polling
 * berikutnya yang menemukan kondisi sama akan MEMPERBARUI alarm yang sama
 * ([reassert]) alih-alih membuat yang baru. ONU mati semalaman menghasilkan satu
 * alarm dengan `occurrenceCount` naik, bukan ratusan baris.
 */
class Alarm private constructor(
    val id: UUID,
    val tenantId: UUID,
    val kind: AlarmKind,
    val entityType: AlarmEntityType,
    val entityId: UUID,
    val entityLabel: String,
    severity: AlarmSeverity,
    status: AlarmStatus,
    message: String,
    measuredValue: Double?,
    val raisedAt: Instant,
    lastSeenAt: Instant,
    clearedAt: Instant?,
    acknowledgedAt: Instant?,
    acknowledgedBy: UUID?,
    occurrenceCount: Int,
) {
    var severity: AlarmSeverity = severity
        private set

    var status: AlarmStatus = status
        private set

    var message: String = message
        private set

    var measuredValue: Double? = measuredValue
        private set

    var lastSeenAt: Instant = lastSeenAt
        private set

    var clearedAt: Instant? = clearedAt
        private set

    var acknowledgedAt: Instant? = acknowledgedAt
        private set

    var acknowledgedBy: UUID? = acknowledgedBy
        private set

    var occurrenceCount: Int = occurrenceCount
        private set

    /** Lamanya alarm terbuka; dipakai UI untuk menyorot gangguan yang berlarut. */
    fun openDuration(now: Instant = Instant.now()): java.time.Duration =
        java.time.Duration.between(raisedAt, clearedAt ?: now)

    /**
     * Kondisi yang sama terdeteksi lagi. Tingkat keparahan boleh naik (redaman
     * yang tadinya WARNING kini CRITICAL) tapi TIDAK diturunkan selama alarm masih
     * terbuka — kondisi yang naik-turun di sekitar ambang tidak boleh membuat
     * alarm tampak membaik padahal masalahnya belum disentuh.
     */
    fun reassert(severity: AlarmSeverity, message: String, measuredValue: Double?, at: Instant = Instant.now()) {
        if (status == AlarmStatus.CLEARED) {
            throw ConflictException("Alarm yang sudah selesai tidak bisa diaktifkan lagi; angkat alarm baru")
        }
        if (severity.ordinal > this.severity.ordinal) this.severity = severity
        this.message = message
        this.measuredValue = measuredValue
        this.lastSeenAt = at
        this.occurrenceCount += 1
    }

    /**
     * Menilai ulang keparahan karena **aturannya** yang berubah, bukan kondisinya.
     *
     * Satu-satunya jalan keparahan boleh TURUN selagi alarm terbuka. [reassert]
     * sengaja menolak menurunkan supaya nilai yang bergoyang di sekitar ambang tak
     * membuat gangguan tampak membaik; tapi kalau operator sendiri yang menggeser
     * ambangnya, alarm lama yang tetap CRITICAL menurut ukuran yang sudah dibuang
     * akan membuat layar & peta berbohong sampai ada bacaan baru. Umur alarm
     * ([raisedAt]) tak disentuh: masalahnya memang sudah selama itu.
     */
    fun reassess(severity: AlarmSeverity) {
        if (status == AlarmStatus.CLEARED) return
        this.severity = severity
    }

    /** Operator mengakui alarm: tetap terbuka, tapi berhenti menuntut perhatian. */
    fun acknowledge(userId: UUID, at: Instant = Instant.now()) {
        if (status == AlarmStatus.CLEARED) throw ConflictException("Alarm sudah selesai")
        if (status == AlarmStatus.ACKNOWLEDGED) return
        status = AlarmStatus.ACKNOWLEDGED
        acknowledgedAt = at
        acknowledgedBy = userId
    }

    /** Kondisinya sudah pulih. Idempotent agar aman dipanggil tiap siklus polling. */
    fun clear(at: Instant = Instant.now()) {
        if (status == AlarmStatus.CLEARED) return
        status = AlarmStatus.CLEARED
        clearedAt = at
    }

    companion object {
        fun raise(
            tenantId: UUID,
            kind: AlarmKind,
            entityId: UUID,
            entityLabel: String,
            severity: AlarmSeverity,
            message: String,
            measuredValue: Double? = null,
            at: Instant = Instant.now(),
        ): Alarm = Alarm(
            id = UuidV7.generate(),
            tenantId = tenantId,
            kind = kind,
            entityType = kind.entityType,
            entityId = entityId,
            entityLabel = entityLabel,
            severity = severity,
            status = AlarmStatus.ACTIVE,
            message = message,
            measuredValue = measuredValue,
            raisedAt = at,
            lastSeenAt = at,
            clearedAt = null,
            acknowledgedAt = null,
            acknowledgedBy = null,
            occurrenceCount = 1,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            kind: AlarmKind,
            entityType: AlarmEntityType,
            entityId: UUID,
            entityLabel: String,
            severity: AlarmSeverity,
            status: AlarmStatus,
            message: String,
            measuredValue: Double?,
            raisedAt: Instant,
            lastSeenAt: Instant,
            clearedAt: Instant?,
            acknowledgedAt: Instant?,
            acknowledgedBy: UUID?,
            occurrenceCount: Int,
        ): Alarm = Alarm(
            id, tenantId, kind, entityType, entityId, entityLabel, severity, status, message,
            measuredValue, raisedAt, lastSeenAt, clearedAt, acknowledgedAt, acknowledgedBy, occurrenceCount,
        )
    }
}
