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
        platform("platform.tenant.delete", "Hapus tenant permanen beserta seluruh datanya")
        // SaaS billing platform: setelan gateway global + langganan tenant ke aplikasi.
        platform("platform.billing.view", "Lihat setelan billing & payment gateway platform")
        platform("platform.billing.manage", "Kelola gateway aktif & kredensial billing platform")
        platform("platform.subscription.view", "Lihat langganan & tagihan tenant ke aplikasi")
        platform("platform.subscription.manage", "Kelola biaya bulanan, tagihan & pembayaran langganan tenant")
        // Kesehatan proses server sendiri (pekerjaan latar) — lintas-tenant, bukan urusan ISP.
        platform("platform.ops.view", "Lihat kesehatan pekerjaan latar server")
        // Relay SMTP + tampilan bawaan email: satu untuk semua tenant, jadi wewenang platform.
        platform("platform.email.view", "Lihat setelan email & template platform")
        platform("platform.email.manage", "Kelola SMTP, logo & template email platform")

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
        perm("network.odf.view", "Lihat ODF")
        perm("network.odf.create", "Tambah ODF")
        perm("network.odf.update", "Ubah ODF")
        perm("network.odf.delete", "Hapus ODF")
        perm("network.odc.view", "Lihat ODC")
        perm("network.odc.create", "Tambah ODC")
        perm("network.odc.update", "Ubah ODC")
        perm("network.odc.delete", "Hapus ODC")
        perm("network.odp.view", "Lihat ODP")
        perm("network.odp.create", "Tambah ODP")
        perm("network.odp.update", "Ubah ODP")
        perm("network.odp.delete", "Hapus ODP")
        perm("network.jointbox.view", "Lihat joint box")
        perm("network.jointbox.create", "Tambah joint box")
        perm("network.jointbox.update", "Ubah joint box")
        perm("network.jointbox.delete", "Hapus joint box")
        perm("network.cable.view", "Lihat kabel")
        perm("network.cable.create", "Tambah kabel")
        perm("network.cable.update", "Ubah kabel")
        perm("network.cable.delete", "Hapus kabel")
        perm("network.splitter.view", "Lihat splitter")
        perm("network.splitter.create", "Tambah splitter")
        perm("network.splitter.update", "Ubah splitter")
        perm("network.splitter.delete", "Hapus splitter")
        perm("network.splice.view", "Lihat sambungan serat")
        perm("network.splice.manage", "Sambung/putus serat")
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

        perm("order.order.view", "Lihat order")
        perm("order.order.create", "Buat order")
        perm("order.order.manage", "Kelola status order")

        perm("inventory.location.view", "Lihat gudang dan bin")
        perm("inventory.location.manage", "Kelola gudang dan bin")
        perm("inventory.item.view", "Lihat item, SKU, lot, dan aset serial")
        perm("inventory.item.manage", "Kelola item, SKU, lot, dan aset serial")
        perm("inventory.custody.view", "Lihat status dan pemegang aset")
         perm("inventory.custody.manage", "Kelola status dan klaim custody aset")
         perm("inventory.approval.view", "Lihat persetujuan pergerakan inventory")
         perm("inventory.approval.request", "Ajukan persetujuan pergerakan inventory")
         perm("inventory.approval.decide", "Setujui/tolak pergerakan inventory")
         perm("inventory.approval.emergency", "Gunakan override darurat persetujuan inventory")
         perm("inventory.approval.manage", "Kelola matriks persetujuan inventory")

        // Portal self-service pelanggan (operator menyiapkan/mereset kredensial login pelanggan)
        perm("portal.credential.view", "Lihat status kredensial portal pelanggan")
        perm("portal.credential.manage", "Buat/reset/nonaktifkan kredensial portal pelanggan")

        // Monitoring
        perm("monitoring.dashboard.view", "Buka dashboard monitoring")
        perm("monitoring.metric.view", "Lihat metrik optik/ONU")
        perm("monitoring.alarm.view", "Lihat alarm")
        perm("monitoring.alarm.ack", "Acknowledge alarm")
        perm("monitoring.threshold.manage", "Setel ambang alarm")
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
        perm("workorder.order.field", "Kerjakan work order lapangan yang ditugaskan ke diri sendiri")
        perm("workorder.evidence.view", "Lihat bukti pengerjaan")
        perm("workorder.evidence.manage", "Unggah/hapus bukti pengerjaan")
        perm("fieldservice.visit.view", "Lihat kunjungan lapangan")
        perm("fieldservice.visit.manage", "Jalankan perintah kunjungan lapangan")
        perm("fieldservice.session.view", "Lihat sesi kerja lapangan")
        perm("hris.employee.self", "Lihat dan kelola profil kepegawaian sendiri yang diizinkan")
        perm("hris.employee.view", "Lihat profil pegawai dengan penyaringan privasi")
        perm("hris.employee.manage", "Kelola profil dan penugasan efektif pegawai")
        perm("hris.attendance.view", "Lihat sesi kehadiran sesuai lingkup privasi")
        perm("hris.attendance.correct", "Ajukan koreksi kehadiran")
        perm("hris.attendance.review", "Tinjau dan putuskan koreksi kehadiran")
        perm("hris.period.close", "Tutup periode kehadiran")
         perm("hris.period.reopen", "Buka kembali periode kehadiran dengan alasan")
         perm("fieldservice.gps.review", "Tinjau keputusan GPS lapangan")
         perm("fieldservice.gps.exact", "Lihat koordinat GPS persis untuk keperluan lapangan")

         perm("payroll.run.view", "Lihat payroll run sesuai lingkup privasi")
         perm("payroll.run.calculate", "Hitung payroll run")
         perm("payroll.run.review", "Tinjau payroll run")
         perm("payroll.run.approve", "Setujui payroll run")
         perm("payroll.run.pay", "Bayar payroll run")
         perm("payroll.run.void", "Void/reversal payroll run")
         perm("payroll.period.manage", "Tutup/buka periode payroll")
         perm("payroll.payslip.self", "Lihat payslip sendiri")
         perm("payroll.payslip.view", "Lihat payslip dengan redaksi payroll")

        // Helpdesk (keluhan yang dilaporkan pelanggan sendiri dari portal)
        perm("helpdesk.ticket.view", "Lihat tiket bantuan pelanggan")
        perm("helpdesk.ticket.reply", "Balas tiket bantuan pelanggan")
        perm("helpdesk.ticket.manage", "Ubah status & eskalasi tiket ke work order")

        // CPE (router/ONT pelanggan via GenieACS)
        perm("cpe.device.view", "Lihat perangkat CPE pelanggan")
        perm("cpe.device.reboot", "Reboot perangkat CPE")
        perm("cpe.device.manage", "Reset pabrik & refresh koneksi ACS CPE")
        perm("cpe.wifi.view", "Lihat WiFi & host tersambung")
        perm("cpe.wifi.manage", "Ubah SSID/password WiFi")
        perm("cpe.diagnostic.run", "Jalankan diagnostik CPE (ping & uji kecepatan)")
        perm("cpe.firmware.manage", "Lihat & pasang firmware CPE")
        // Sengaja berakhiran `.view`: AccessChecker menganggap segala kode non-`.view`
        // sebagai tulis dan menolaknya 402 saat langganan tenant terkunci — padahal ini
        // cuma membaca alamat CWMP dari env. Diberikan juga ke role Teknisi.
        perm("cpe.acs.view", "Lihat informasi server ACS (URL CWMP & status)")

        // Catalog (paket internet: sumber tunggal harga + kecepatan + QoS + FUP)
        perm("catalog.plan.view", "Lihat paket internet")
        perm("catalog.plan.manage", "Kelola paket internet")

        // BNG (BRAS/RADIUS: registri BRAS, akun PPPoE pelanggan; katalog paket pindah ke catalog.plan.*)
        perm("bng.nas.view", "Lihat registri BRAS")
        perm("bng.nas.manage", "Kelola registri BRAS")
        perm("bng.access.view", "Lihat akun PPPoE pelanggan")
        perm("bng.access.manage", "Kelola akun PPPoE pelanggan")
        perm("bng.access.isolate", "Isolir/pulihkan akses jaringan pelanggan")
        perm("bng.session.view", "Lihat sesi PPPoE & tren trafik pelanggan")
        perm("bng.session.reset", "Reset Login (putus sesi PPPoE) pelanggan")

        // Provisioning InterVLAN — sertifikasi adapter berdampak lintas-tenant dan hanya dikelola platform.
        perm("provisioning.segment.view", "Lihat profil segmen VLAN")
        perm("provisioning.segment.manage", "Kelola profil segmen VLAN")
        perm("provisioning.plan.view", "Lihat rencana provisioning")
        perm("provisioning.execution.apply", "Terapkan rencana provisioning")
        perm("provisioning.execution.cancel", "Batalkan eksekusi provisioning")
        perm("provisioning.drift.view", "Lihat drift perangkat")
        perm("provisioning.drift.adopt", "Adopsi drift perangkat")
        platform("provisioning.certification.manage", "Kelola sertifikasi adapter perangkat")

        perm("hotspot.site.view", "Lihat hotspot site")
        perm("hotspot.site.manage", "Kelola hotspot site")
        perm("hotspot.voucher.view", "Lihat voucher hotspot")
        perm("hotspot.voucher.manage", "Kelola voucher hotspot")
        perm("hotspot.session.view", "Lihat sesi hotspot")

        // Billing (tagihan, pembayaran, auto-isolir/auto-pulih)
        perm("billing.invoice.view", "Lihat tagihan & pembayaran")
        perm("billing.invoice.manage", "Terbitkan/batalkan tagihan")
        perm("billing.payment.manage", "Catat pembayaran manual")
        // Izin tersendiri, bukan menumpang billing.payment.manage: mengembalikan uang keluar dari
        // rekening tenant, jadi kasir yang boleh mencatat pembayaran masuk belum tentu boleh ini.
        perm("billing.refund.manage", "Ajukan & tutup pengembalian dana (refund)")
        perm("billing.gateway.view", "Lihat setelan payment gateway")
        perm("billing.gateway.manage", "Kelola penyedia & kredensial payment gateway")
        perm("billing.tax.view", "Lihat setelan pajak & kewajiban BHP/USO")
        perm("billing.tax.manage", "Kelola PPN & kontribusi BHP/USO")
        // Langganan SaaS sisi tenant: lihat masa aktif/tagihan sendiri + perpanjang mandiri.
        perm("billing.subscription.view", "Lihat langganan aplikasi (masa aktif, tagihan)")
        perm("billing.subscription.renew", "Perpanjang langganan aplikasi (buat tagihan & bayar)")

        // VPN — hub/server adalah infrastruktur PLATFORM (dikelola admin platform), akun milik tenant
        platform("vpn.server.view", "Lihat server VPN platform")
        platform("vpn.server.manage", "Kelola server VPN platform")
        perm("vpn.peer.view", "Lihat akun VPN")
        perm("vpn.peer.manage", "Generate & kelola akun VPN")
        perm("vpn.config.view", "Unduh config VPN (.ovpn & RouterOS) berisi kredensial")

        // Notification
        perm("notification.template.view", "Lihat template notifikasi")
        perm("notification.template.manage", "Kelola template notifikasi")
        perm("notification.broadcast.send", "Kirim broadcast ke pelanggan")
        perm("notification.broadcast.view", "Lihat riwayat broadcast")
        perm("notification.settings.view", "Lihat setelan gateway & pemicu notifikasi")
        perm("notification.settings.manage", "Kelola gateway WA & saklar pemicu notifikasi")

        // Laporan & analitik (agregasi lintas-domain: keuangan + langganan)
        perm("reporting.report.view", "Lihat laporan & analitik")

        // Portabilitas data tenant — satu unduhan berisi SELURUH basis data pelanggan tenant,
        // jadi izinnya berdiri sendiri: yang boleh melihat data sehari-hari belum tentu boleh
        // membawa semuanya keluar sekaligus.
        perm("tenancy.data.export", "Unduh arsip seluruh data tenant (offboarding)")

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
