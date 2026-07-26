package com.duluin.ftth.iam.domain.catalog

import com.duluin.ftth.iam.domain.model.vo.PermissionCode

/**
 * Katalog SELURUH izin yang dikenal sistem, dideklarasikan di kode (type-safe,
 * tidak bisa typo) dan di-seed ke DB saat startup.
 *
 * Sengaja sudah memuat izin untuk module yang belum diimplementasikan (network,
 * monitoring, incident, dst) supaya role-builder menampilkan gambaran utuh sejak
 * awal — permukaan RBAC tumbuh dengan menambah baris di sini, bukan mengubah skema.
 */
object PermissionCatalog {

    val ALL: List<PermissionDefinition> = buildList {
        // Platform (lintas-tenant, hanya platform admin)
        platform("platform.tenant.view", "Lihat daftar tenant")
        platform("platform.tenant.create", "Onboarding tenant baru beserta admin awal")
        platform("platform.tenant.manage", "Suspend/aktifkan tenant")

        // IAM
        perm("iam.user.view", "Lihat pengguna")
        perm("iam.user.create", "Tambah pengguna")
        perm("iam.user.update", "Ubah pengguna")
        perm("iam.user.delete", "Hapus pengguna")
        perm("iam.user.assign", "Atur role & area pengguna")
        perm("iam.role.view", "Lihat role")
        perm("iam.role.create", "Buat role")
        perm("iam.role.update", "Ubah role & izinnya")
        perm("iam.role.delete", "Hapus role")
        perm("iam.area.view", "Lihat area/wilayah")
        perm("iam.area.create", "Buat area")
        perm("iam.area.update", "Ubah area")
        perm("iam.area.delete", "Hapus area")
        perm("iam.permission.view", "Lihat katalog izin")

        // Network inventory
        perm("network.site.view", "Lihat site/POP")
        perm("network.site.create", "Tambah site/POP")
        perm("network.site.update", "Ubah site/POP")
        perm("network.site.delete", "Hapus site/POP")
        perm("network.olt.view", "Lihat OLT")
        perm("network.olt.create", "Tambah OLT")
        perm("network.olt.update", "Ubah OLT")
        perm("network.olt.delete", "Hapus OLT")
        perm("network.odc.view", "Lihat ODC")
        perm("network.odc.create", "Tambah ODC")
        perm("network.odc.update", "Ubah ODC")
        perm("network.odc.delete", "Hapus ODC")
        perm("network.odp.view", "Lihat ODP")
        perm("network.odp.create", "Tambah ODP")
        perm("network.odp.update", "Ubah ODP")
        perm("network.odp.delete", "Hapus ODP")
        perm("network.cable.view", "Lihat kabel")
        perm("network.cable.create", "Tambah kabel")
        perm("network.cable.update", "Ubah kabel")
        perm("network.cable.delete", "Hapus kabel")
        perm("network.splitter.view", "Lihat splitter")
        perm("network.splitter.update", "Ubah splitter")
        perm("network.otdr.view", "Lihat hasil uji OTDR")
        perm("network.otdr.record", "Catat/hapus hasil uji OTDR")

        // GIS
        perm("gis.map.view", "Buka peta jaringan")
        perm("gis.layer.manage", "Kelola layer peta")

        // Customer
        perm("customer.customer.view", "Lihat pelanggan")
        perm("customer.customer.create", "Tambah pelanggan")
        perm("customer.customer.update", "Ubah pelanggan")
        perm("customer.customer.delete", "Hapus pelanggan")
        perm("customer.subscription.view", "Lihat langganan")
        perm("customer.subscription.update", "Ubah langganan/isolir")
        perm("customer.onu.view", "Lihat ONU pelanggan")
        perm("customer.onu.assign", "Pasang/lepas ONU ke port ODP")

        // Monitoring
        perm("monitoring.dashboard.view", "Buka dashboard monitoring")
        perm("monitoring.metric.view", "Lihat metrik optik/ONU")
        perm("monitoring.alarm.view", "Lihat alarm")
        perm("monitoring.alarm.ack", "Acknowledge alarm")
        perm("monitoring.collector.view", "Lihat collector agent")
        perm("monitoring.collector.manage", "Kelola collector & polling")
        perm("monitoring.provisioning.view", "Lihat kotak masuk ONU terdeteksi")
        perm("monitoring.provisioning.manage", "Provisi/abaikan ONU terdeteksi")

        // Incident
        perm("incident.ticket.view", "Lihat tiket insiden")
        perm("incident.ticket.create", "Buat tiket insiden")
        perm("incident.ticket.update", "Ubah tiket insiden")
        perm("incident.ticket.assign", "Assign tiket ke teknisi")
        perm("incident.ticket.close", "Tutup tiket insiden")
        perm("incident.sla.manage", "Kelola kebijakan SLA & eskalasi")

        // Work order
        perm("workorder.order.view", "Lihat work order")
        perm("workorder.dashboard.view", "Buka dashboard dispatch")
        perm("workorder.order.create", "Buat work order")
        perm("workorder.order.update", "Ubah work order")
        perm("workorder.order.assign", "Assign work order")
        perm("workorder.order.close", "Selesaikan work order")
        perm("workorder.order.approve", "Setujui/tolak hasil kerja")
        perm("workorder.evidence.view", "Lihat bukti pengerjaan")
        perm("workorder.evidence.manage", "Unggah/hapus bukti pengerjaan")

        // CPE (router/ONT pelanggan via GenieACS)
        perm("cpe.device.view", "Lihat perangkat CPE pelanggan")
        perm("cpe.device.reboot", "Reboot perangkat CPE")
        perm("cpe.wifi.view", "Lihat WiFi & host tersambung")
        perm("cpe.wifi.manage", "Ubah SSID/password WiFi")
        perm("cpe.diagnostic.run", "Jalankan diagnostik CPE (ping & uji kecepatan)")

        // Notification
        perm("notification.template.view", "Lihat template notifikasi")
        perm("notification.template.manage", "Kelola template notifikasi")
        perm("notification.broadcast.send", "Kirim broadcast ke pelanggan")
        perm("notification.broadcast.view", "Lihat riwayat broadcast")

        // Audit
        perm("audit.log.view", "Lihat jejak audit")

        // Dashboard
        perm("dashboard.overview.view", "Buka ringkasan/overview")
    }

    /** Semua kode izin, untuk validasi cepat. */
    val codes: Set<String> = ALL.mapTo(HashSet()) { it.code.value }

    /** Izin yang boleh dipakai role tenant biasa (mengecualikan izin platform). */
    fun tenantAssignable(): List<PermissionDefinition> = ALL.filterNot { it.platformOnly }

    private fun MutableList<PermissionDefinition>.perm(code: String, description: String) {
        add(PermissionDefinition(PermissionCode.of(code), description, platformOnly = false))
    }

    private fun MutableList<PermissionDefinition>.platform(code: String, description: String) {
        add(PermissionDefinition(PermissionCode.of(code), description, platformOnly = true))
    }
}
