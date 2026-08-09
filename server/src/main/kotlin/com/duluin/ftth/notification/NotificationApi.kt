package com.duluin.ftth.notification

/**
 * Kontrak publik module notification untuk module lain yang perlu mengirim pesan
 * TRANSAKSIONAL — pesan yang ditujukan ke SATU orang, lahir dari tindakan orang itu sendiri,
 * dan isinya bukan konsumsi operator.
 *
 * Sengaja terpisah dari jalur broadcast (`SendBroadcastUseCase`) dan tidak menyentuh riwayat
 * [com.duluin.ftth.notification.domain.model.Broadcast] sama sekali. Riwayat broadcast
 * menyimpan isi pesan apa adanya agar operator bisa menelusuri "apa yang kita kirim ke
 * pelanggan"; pesan transaksional seperti kode pemulihan password justru TIDAK boleh terbaca
 * operator. Menuliskannya ke riwayat sama saja membocorkan kunci akun pelanggan ke seluruh
 * staf ISP.
 *
 * Yang tetap dicatat adalah PERISTIWA-nya (siapa, kanal apa, berhasil/tidak) — di jejak audit
 * module pemanggil, tanpa isi pesan.
 */
interface NotificationApi {

    /**
     * Kirim satu pesan transaksional lewat kanal yang diminta.
     *
     * Dipanggil di dalam [com.duluin.ftth.common.tenant.TenantContext.runAs] tenant yang
     * bersangkutan: kanal WhatsApp memakai gateway milik ISP itu sendiri, jadi resolusinya
     * butuh tenant aktif. Kanal email memakai SMTP platform sehingga tak bergantung tenant.
     *
     * Tak pernah melempar karena transport gagal — kegagalan dilaporkan lewat
     * [TransactionalDelivery] agar pemanggil bisa memutuskan sendiri (mencoba kanal lain,
     * atau tetap menjawab netral demi anti-enumerasi).
     */
    fun sendTransactional(message: TransactionalMessage): TransactionalDelivery
}

/** Kanal pesan transaksional. Tak termasuk SMS: belum ada penyedianya di sistem ini. */
enum class TransactionalChannel { EMAIL, WHATSAPP }

/**
 * Untuk apa pesan ini dikirim. Dipetakan module notification ke pemicu internalnya sehingga
 * ISP bisa memasangkan template WhatsApp yang sudah disetujui — pemanggil tak perlu tahu
 * soal template maupun penyedia.
 */
enum class TransactionalPurpose { PORTAL_PASSWORD_RESET }

/**
 * @param destination alamat email atau nomor WhatsApp, sesuai [channel].
 * @param recipientName nama penerima; hanya sebagian penyedia WhatsApp yang mewajibkannya.
 * @param subject baris subjek email. Diabaikan kanal WhatsApp (tak punya padanannya).
 */
data class TransactionalMessage(
    val purpose: TransactionalPurpose,
    val channel: TransactionalChannel,
    val destination: String,
    val recipientName: String,
    val subject: String,
    val body: String,
)

/**
 * Hasil satu upaya kirim. [detail] adalah keterangan teknis singkat untuk log/audit — jangan
 * ditampilkan apa adanya ke pengguna akhir.
 */
data class TransactionalDelivery(
    val delivered: Boolean,
    val detail: String?,
)
