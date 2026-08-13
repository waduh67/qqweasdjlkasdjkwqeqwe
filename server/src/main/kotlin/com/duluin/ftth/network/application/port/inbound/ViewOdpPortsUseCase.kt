package com.duluin.ftth.network.application.port.inbound

import java.util.UUID

/**
 * Papan port sebuah ODP — pertanyaan yang paling sering diajukan di depan kotak
 * yang terbuka: "lubang ini punya siapa, dan kaki mana yang menyalurkannya?"
 *
 * Dipisahkan dari [ManageOdpUseCase] karena sifatnya berbeda: yang itu mengurus
 * identitas & letak kotak, yang ini menyandingkan dua catatan milik dua module
 * berbeda dan menunjukkan selisihnya. Menempelkannya ke sana akan menyeret
 * ketergantungan module customer ke seluruh pengelolaan ODP.
 */
interface ViewOdpPortsUseCase {

    fun ports(odpId: UUID): OdpPortBoardView
}
