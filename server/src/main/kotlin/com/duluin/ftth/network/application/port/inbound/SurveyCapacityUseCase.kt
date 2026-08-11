package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate

/**
 * "Alamat ini bisa dipasang atau tidak?" — dijawab dari data, di tempat, saat
 * ditanya.
 *
 * Hari ini pertanyaan itu dijawab dengan menelepon teknisi yang kebetulan hafal
 * daerahnya. Jawabannya sering keliru ke dua arah: sales menjanjikan pemasangan
 * di alamat yang kotaknya sudah penuh, atau menolak calon pelanggan padahal ada
 * selubung lewat di depan gangnya dengan enam core menganggur.
 *
 * Yang dilaporkan karena itu DUA lapis, dan urutannya bukan kebetulan:
 *
 * 1. Kotak yang siap pakai — pasang hari itu juga, cukup satu drop.
 * 2. Selubung yang lewat di dekat situ beserta core kosongnya — tak bisa hari
 *    itu, tapi bisa: kupas di tengah bentang, pasang kotak baru, tanpa menarik
 *    kabel baru dari kabinet.
 *
 * Lapis kedua inilah yang selama ini tak terlihat di layar mana pun, padahal ia
 * yang membedakan "tidak bisa" dengan "bisa minggu depan".
 */
interface SurveyCapacityUseCase {

    /**
     * @param location titik calon pelanggan — hasil klik peta atau koordinat GPS
     *        petugas survey.
     * @param radiusMeters seberapa jauh masih pantas ditarik drop/dikupas.
     * @param limit banyaknya kotak & kabel teratas yang dilaporkan.
     */
    fun nearby(location: Coordinate, radiusMeters: Double, limit: Int): SurveyCapacityView
}
