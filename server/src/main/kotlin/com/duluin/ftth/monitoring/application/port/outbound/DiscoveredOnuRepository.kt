package com.duluin.ftth.monitoring.application.port.outbound

import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import java.util.UUID

/**
 * Port keluar untuk kotak masuk ONU terdeteksi. Dedup per serial ditegakkan di
 * lapisan ini (satu baris per serial per tenant) sehingga siklus polling yang
 * berulang memperbarui baris yang sama, bukan menumpuk duplikat.
 */
interface DiscoveredOnuRepository {

    fun save(discovered: DiscoveredOnu): DiscoveredOnu

    fun findById(id: UUID): DiscoveredOnu?

    /** Baris untuk sebuah serial di tenant aktif, apa pun state-nya — untuk upsert saat ingestion. */
    fun findBySerialNumber(serialNumber: String): DiscoveredOnu?

    /** Isi kotak masuk untuk sebuah state, terbaru dulu. */
    fun findByState(state: DiscoveredOnuState): List<DiscoveredOnu>

    /**
     * Baris yang MASIH menunggu (DISCOVERED) untuk serial yang kini sudah dikenal —
     * dipakai ingestion untuk menuntaskan sendiri kotak masuk begitu serial
     * didaftarkan lewat jalur mana pun.
     */
    fun findDiscoveredBySerials(serialNumbers: Set<String>): List<DiscoveredOnu>
}
