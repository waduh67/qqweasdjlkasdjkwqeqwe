package com.duluin.ftth.notification.application.port.outbound

/**
 * Port keluar untuk mengirim satu email.
 *
 * Berbeda tajam dari [MessageDispatcher] yang WhatsApp-nya BAWA-SENDIRI per tenant: SMTP di
 * sini milik PLATFORM, satu untuk semua tenant. Alasannya praktis — nomor WhatsApp adalah
 * identitas pengirim yang dilihat pelanggan (harus nomor ISP-nya sendiri), sedangkan alamat
 * email pengirim tak dituntut begitu, dan meminta tiap ISP kecil menyiapkan SMTP sendiri
 * hanya akan membuat pemulihan password mereka tak pernah aktif.
 *
 * Karena itu port ini tak menerima gateway apa pun: konfigurasinya tunggal dan tinggal di
 * adapter. Kegagalan dilaporkan sebagai [DeliveryOutcome], bukan lemparan, agar pemanggil
 * bisa mencoba kanal lain tanpa membatalkan transaksinya.
 */
interface EmailDispatcher {

    /**
     * @param fromName nama pengirim yang tampil di kotak masuk pelanggan. Diisi nama ISP,
     *        karena alamat pengirimnya milik platform: tanpa ini pelanggan menerima tagihan
     *        internetnya dari nama yang tak pernah ia kenal, yang lebih mirip penipuan
     *        daripada pemberitahuan. Null = pakai nama bawaan platform.
     */
    fun send(to: String, subject: String, body: String, fromName: String? = null): DeliveryOutcome
}
