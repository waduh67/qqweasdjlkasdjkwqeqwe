package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Apa yang DIPERBUAT orang terhadap selubung kabel di sebuah simpul.
 *
 * Inilah yang menentukan sebuah kabel boleh disambung di sebuah kotak — bukan
 * jaraknya dari kotak itu. Kabel yang melintas persis di atas ODP tetap tak
 * bisa disambung selama selubungnya belum dibuka, dan kabel yang rutenya
 * digambar melenceng lima puluh meter tetap bisa disambung kalau kenyataannya
 * memang dikupas di sana.
 */
enum class CableAttachmentRole(val label: String) {
    /** Selubung habis di sini: kabelnya berhenti, seluruh core terbuka. */
    END("Berhenti di sini"),

    /**
     * Dikupas di tengah bentang (mid-span tapping): sebagian core diambil untuk
     * kotak ini, sisanya jalan terus tanpa terputus. Bentuk paling lazim pada
     * kabel distribusi yang menyusuri satu gang berisi banyak ODP.
     */
    TAPPED("Dikupas di sini"),

    /**
     * Cuma numpang lewat atau digulung di dalam kotak — selubungnya UTUH.
     *
     * Tak bisa disambung, dan justru karena itu wajib tercatat: teknisi yang
     * membuka kotak menemukan kabel ini di dalamnya, dan tanpa catatan bahwa
     * ia sekadar lewat, kabel orang lain gampang dikira milik kotak ini lalu
     * dipotong. Sekaligus jawaban atas "masih ada sisa core lewat sini?" saat
     * kotak baru mau disisipkan di tengah jalur.
     */
    PASSING("Cuma lewat (utuh)"),
    ;

    /** Selubung sudah terbuka di sini, jadi core-nya bisa disambung. */
    val spliceable: Boolean get() = this != PASSING
}

/**
 * Satu simpul yang disinggahi kabel, pada urutan tertentu sepanjang rutenya.
 *
 * URUTAN adalah posisi elemen ini di dalam [Cable.attachments], bukan bidang
 * tersendiri. Nomor yang disimpan sebagai data akan basi diam-diam tiap kali
 * ada singgahan disisipkan atau dicabut; daftar yang terurut tak bisa basi.
 */
data class CableAttachment(
    val id: UUID,
    val node: NetworkEndpoint,
    val role: CableAttachmentRole,
)

/**
 * Singgahan di TENGAH bentang, sebagaimana diminta pemanggil (peta / meja
 * sambung) sebelum punya identitas.
 *
 * Sengaja memakai [NetworkNodeRef] yang tak mengenal port, bukan
 * [NetworkEndpoint]: kabel yang cuma dikupas di tengah tidak dicolok ke port
 * mana pun — yang bertemu di sana core dengan core. Dengan tipe ini keadaan
 * mustahil itu tak bisa ditulis sejak awal, tak perlu divalidasi belakangan.
 */
data class CableWaypoint(
    val node: NetworkNodeRef,
    val role: CableAttachmentRole,
) {
    init {
        if (role == CableAttachmentRole.END) {
            throw ValidationException(
                "Simpul di tengah bentang tak bisa berperan sebagai ujung kabel. " +
                    "Pilih '${CableAttachmentRole.TAPPED.label}' bila selubungnya dibuka di sana, " +
                    "atau '${CableAttachmentRole.PASSING.label}' bila cuma melintas.",
            )
        }
    }
}
