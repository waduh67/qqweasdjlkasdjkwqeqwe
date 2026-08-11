package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.network.domain.model.ClosureKind
import java.util.UUID

/**
 * Menelusuri jalur serat ke arah HULU — dari sebuah titik sampai port PON yang
 * menyuapinya — sambil menjumlahkan anggaran redamannya.
 *
 * Sampai sekarang pertanyaan "kenapa ODP ini gelap" dan "berapa redaman sampai
 * pelanggan itu" dijawab dengan menebak dari gambar peta. Padahal jawabannya
 * sudah ada di data sambungan sejak potongan B: tiap kotak mencatat serat mana
 * bertemu serat mana, dan merangkainya berurutan menghasilkan rantai fisik yang
 * sesungguhnya — termasuk saat rantai itu ternyata putus di tengah.
 *
 * Arah hulu dipilih (bukan hilir) karena satu titik hilir selalu punya TEPAT
 * SATU jalur ke sumbernya, sedangkan ke arah hilir sebuah splitter memecahnya
 * jadi puluhan cabang. Satu jalur bisa dibaca sebagai daftar; percabangan butuh
 * bentuk lain, dan itu urusan potongan berikutnya.
 */
interface TraceFiberPathUseCase {

    /**
     * Telusur dari sebuah titik sambungan. Titik CORE boleh ditunjuk meski kedua
     * ujungnya sama-sama tersambung: kedua arah dicoba, dan yang sampai ke OLT
     * yang dipakai.
     */
    fun traceUpstream(point: ConnectionPointCommand): FiberPathView

    /**
     * Telusur dari sebuah kotak: satu jalur untuk tiap titik yang punya hulu.
     *
     * Inilah bentuk yang dipakai orang di lapangan — mereka membuka detail ODP,
     * bukan menghafal id modul splitter di dalamnya. ODC/ODP menghasilkan satu
     * jalur per modul (lewat kaki masuknya), ODF satu jalur per port terpakai.
     * Joint box tak punya titik simpul sendiri sehingga daftarnya kosong; yang
     * lewat di sana terlihat sebagai hop pada jalur kotak di hilirnya.
     */
    fun traceClosure(closureKind: ClosureKind, closureId: UUID): List<FiberPathView>

    /**
     * Arah sebaliknya, dan karena itu bentuknya juga sebaliknya: dari sebuah port
     * PON ke seluruh yang tergantung di bawahnya.
     *
     * Hasilnya bukan daftar jalur melainkan REKAP, sebab yang ditanya di sini
     * bukan "lewat mana" melainkan "berapa banyak" — satu port GPON cuma sanggup
     * menjadwalkan 64 ONU, dan tak ada satu pun layar yang selama ini menunjukkan
     * angka itu. Kabinet ditambah satu per satu, dan plafonnya baru ketahuan saat
     * ONU pelanggan baru menolak daftar dengan teknisi sudah di lokasi.
     */
    fun tracePonPortLoad(ponPortId: UUID): PonPortLoadView
}
