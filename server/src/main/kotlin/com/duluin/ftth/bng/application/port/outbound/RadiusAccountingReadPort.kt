package com.duluin.ftth.bng.application.port.outbound

import com.duluin.ftth.bng.domain.model.SessionObservation
import java.util.UUID

/**
 * Jalur-BACA RADIUS server-side (RADIUS-as-a-service): membaca sesi hidup dari `radacct`
 * langsung di radius-db platform co-located. Menggantikan feed sesi collector on-prem —
 * collector tak punya rute ke radius-db internal.
 *
 * Kunci isolasi: baris `radacct` ber-username `{kodeTenant}:{username}` (S0, lewat
 * `sql_user_name`). Implementasi MEMFILTER per [tenantCode] lalu MENGUPAS prefiksnya
 * sehingga [SessionObservation.username] kembali bare — radius-db tak ber-RLS, jadi
 * penyekatan tenant dilakukan di query, bukan lewat GUC seperti datasource aplikasi.
 */
interface RadiusAccountingReadPort {

    /** True bila radius-db dikonfigurasi — jalur-baca server-side aktif. */
    fun isConfigured(): Boolean

    /**
     * Sesi PPPoE yang masih hidup (`acctstoptime IS NULL`) milik tenant [tenantId],
     * disaring lewat [tenantCode] (= slug/`nas.shortname`) dan di-dedup per akun
     * (baris terbaru menang). [tenantId] menyertai sebagai jahitan sharding radius-db.
     */
    fun activeSessions(tenantId: UUID, tenantCode: String): List<SessionObservation>
}
