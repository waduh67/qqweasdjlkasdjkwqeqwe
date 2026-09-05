package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.onboarding.application.port.inbound.CustomerExportLine
import com.duluin.ftth.onboarding.application.port.inbound.ExportCustomersUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset

/**
 * Orkestrasi ekspor CSV pelanggan — module daun yang hanya memanggil kontrak publik bng & customer.
 * Anchor = akun jaringan (username): tiap akun jadi satu baris, diperkaya biodata + langganan
 * pemiliknya. Baris yang langganannya tak ter-resolusi (data anomali) dilewati agar ekspor tetap
 * jalan. Murni baca (readOnly) — tak menyentuh RADIUS/router.
 */
@Service
@Transactional(readOnly = true)
class ExportCustomersService(
    private val customerApi: CustomerApi,
    private val bngApi: BngApi,
) : ExportCustomersUseCase {

    override fun exportCustomers(): List<CustomerExportLine> {
        val accesses = bngApi.exportAccesses()
        if (accesses.isEmpty()) return emptyList()
        val rowsBySubscription = customerApi
            .findExportRows(accesses.mapTo(HashSet()) { it.subscriptionId })
            .associateBy { it.subscriptionId }
        return accesses.mapNotNull { access ->
            val row = rowsBySubscription[access.subscriptionId] ?: return@mapNotNull null
            CustomerExportLine(
                name = row.name,
                phone = row.phone,
                address = row.address,
                packageName = row.packageName,
                // Tipe koneksi huruf kecil agar round-trip (impor mengenali substring "pppoe").
                connectionType = access.authType.lowercase(),
                // Tanggal aktivasi (Instant) → tanggal UTC; impor membacanya kembali sebagai LocalDate.
                installationDate = row.activatedAt?.atZone(ZoneOffset.UTC)?.toLocalDate(),
                mikrotikUsername = access.username,
                email = row.email,
                routerName = access.nasName,
                idCardNumber = row.idCardNumber,
                nextBillingDay = row.billingDayOfMonth,
                    latitude = row.location?.latitude,
                    longitude = row.location?.longitude,
                // Framed-IP hanya terisi untuk akun Static/DHCP; null → kolom kosong (round-trip).
                framedIp = access.framedIp,
            )
        }
    }
}
