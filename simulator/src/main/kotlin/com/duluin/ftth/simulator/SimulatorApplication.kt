package com.duluin.ftth.simulator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import java.util.concurrent.CountDownLatch

/**
 * Titik masuk lab simulator. Tiap peniru protokol (OLT SNMP, BRAS/RADIUS)
 * dihidupkan komponennya sendiri, digerbang properti `ftth.sim.*` agar bisa
 * dinyala-matikan tanpa mengubah kode.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class SimulatorApplication

fun main(args: Array<String>) {
    val context = runApplication<SimulatorApplication>(*args)

    // App non-web: SEMUA listener (SNMP snmp4j, responder DAE, mesin RADIUS) jalan di
    // utas DAEMON, jadi begitu main() kembali JVM langsung mati (exit 0) — dan di Docker
    // dengan restart:unless-stopped itu berubah jadi crash-loop tiap beberapa detik.
    // Tahan main (utas NON-daemon) sampai context ditutup agar proses "selalu menyala"
    // seperti perangkat sungguhan; SIGTERM → shutdown hook Spring menutup context →
    // ContextClosedEvent → latch lepas → main selesai → keluar rapi.
    val until = CountDownLatch(1)
    context.addApplicationListener(ApplicationListener<ApplicationEvent> { event ->
        if (event is ContextClosedEvent) until.countDown()
    })
    until.await()
}
