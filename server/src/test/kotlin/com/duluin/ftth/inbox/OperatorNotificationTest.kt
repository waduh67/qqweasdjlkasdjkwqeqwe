package com.duluin.ftth.inbox

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inbox.domain.model.NotificationAudience
import com.duluin.ftth.inbox.domain.model.NotificationKind
import com.duluin.ftth.inbox.domain.model.NotificationSeverity
import com.duluin.ftth.inbox.domain.model.OperatorNotification
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Aturan siapa-boleh-melihat-apa di kotak masuk operator — tanpa Spring, tanpa database.
 *
 * Ini aturan yang ditulis DUA KALI: sekali di Kotlin (dipakai saat menandai terbaca) dan
 * sekali sebagai penyaring SQL di adapter. Uji ini memaku versi Kotlin-nya, karena yang
 * bocor kalau keduanya menyimpang bukan sekadar tampilan: pemberitahuan memuat nama
 * pelanggan dan judul gangguan milik antrean orang lain.
 */
class OperatorNotificationTest {

    private val tenantId: UUID = UUID.randomUUID()
    private val budi: UUID = UUID.randomUUID()
    private val siti: UUID = UUID.randomUUID()

    private fun personal(userId: UUID) = OperatorNotification.personal(
        tenantId = tenantId,
        kind = NotificationKind.WORK_ORDER_ASSIGNED,
        severity = NotificationSeverity.INFO,
        title = "Work order WO-1 ditugaskan ke Anda",
        body = "Pasang baru di Cibiru.",
        link = "/my-work-orders/1",
        userId = userId,
        dedupeKey = "wo-assigned:1:$userId",
    )

    private fun shared(permission: String) = OperatorNotification.forHolders(
        tenantId = tenantId,
        kind = NotificationKind.HELPDESK_SLA,
        severity = NotificationSeverity.WARNING,
        title = "Tiket TKT-1 lewat tenggat balasan",
        body = "Internet mati — Budi.",
        link = "/helpdesk",
        permission = permission,
        dedupeKey = "helpdesk-sla:1:RESPONSE:0",
    )

    private fun audience(userId: UUID, vararg permissions: String, platformAdmin: Boolean = false) =
        NotificationAudience(userId, permissions.toSet(), platformAdmin)

    @Test
    fun `pemberitahuan pribadi hanya terlihat oleh yang dituju`() {
        val notification = personal(budi)

        assertThat(notification.visibleTo(audience(budi))).isTrue()
        assertThat(notification.visibleTo(audience(siti, "workorder.order.view"))).isFalse()
    }

    @Test
    fun `izin sebanyak apa pun tak membuka pemberitahuan pribadi orang lain`() {
        // Yang pribadi tetap pribadi: supervisor boleh melihat SEMUA work order di papannya,
        // tapi "sudah kamu baca belum" milik teknisi bukan urusan yang bisa diambil alih.
        assertThat(personal(budi).visibleTo(audience(siti, "workorder.dashboard.view"))).isFalse()
        assertThat(personal(budi).visibleTo(audience(siti, platformAdmin = true))).isFalse()
    }

    @Test
    fun `pemberitahuan antrean bersama menuntut izin yang disebut`() {
        val notification = shared("helpdesk.ticket.manage")

        assertThat(notification.visibleTo(audience(budi, "helpdesk.ticket.manage"))).isTrue()
        assertThat(notification.visibleTo(audience(siti, "helpdesk.ticket.view"))).isFalse()
        assertThat(notification.visibleTo(audience(siti))).isFalse()
    }

    @Test
    fun `platform admin melewati syarat izin, sama seperti di seluruh aplikasi`() {
        assertThat(shared("helpdesk.ticket.manage").visibleTo(audience(siti, platformAdmin = true))).isTrue()
    }

    @Test
    fun `judul dan isi dipangkas, bukan ditolak, saat melewati batas kolom`() {
        // Isi pemberitahuan dirakit dari data lapangan (nama pelanggan, judul gangguan) yang
        // panjangnya tak bisa dijamin. Gagal menyimpan berarti operator tak diberi tahu sama
        // sekali — jauh lebih buruk daripada kalimat yang terpotong.
        val notification = OperatorNotification.forHolders(
            tenantId = tenantId,
            kind = NotificationKind.INCIDENT_OPENED,
            severity = NotificationSeverity.CRITICAL,
            title = "G".repeat(400),
            body = "B".repeat(900),
            link = null,
            permission = "incident.ticket.view",
            dedupeKey = "incident-opened:1",
        )

        assertThat(notification.title).hasSize(OperatorNotification.MAX_TITLE)
        assertThat(notification.body).hasSize(OperatorNotification.MAX_BODY)
    }

    @Test
    fun `kunci idempoten wajib ada — tanpanya peristiwa yang sama menumpuk tiap ronde`() {
        assertThatThrownBy {
            OperatorNotification.forHolders(
                tenantId = tenantId,
                kind = NotificationKind.INCIDENT_OPENED,
                severity = NotificationSeverity.INFO,
                title = "Gangguan baru",
                body = "-",
                link = null,
                permission = "incident.ticket.view",
                dedupeKey = " ",
            )
        }.isInstanceOf(ValidationException::class.java)
    }
}
