package com.duluin.ftth.helpdesk

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.helpdesk.domain.model.Ticket
import com.duluin.ftth.helpdesk.domain.model.TicketAuthor
import com.duluin.ftth.helpdesk.domain.model.TicketCategory
import com.duluin.ftth.helpdesk.domain.model.TicketPriority
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Aturan tiket helpdesk di lapisan domain — tanpa Spring, tanpa database.
 *
 * Yang dijaga di sini adalah janji yang dibaca pelanggan di portal: tiket yang dinyatakan
 * selesai bisa dibantah, tiket yang ditutup benar-benar berhenti, dan satu keluhan hanya
 * melahirkan satu work order.
 */
class TicketTest {

    private val tenantId: UUID = UUID.randomUUID()
    private val customerId: UUID = UUID.randomUUID()
    private val operatorId: UUID = UUID.randomUUID()
    private val at: Instant = Instant.parse("2026-08-09T02:00:00Z")

    private fun ticket(subject: String = "Internet mati sejak pagi") = Ticket.open(
        tenantId = tenantId,
        customerId = customerId,
        customerName = "Budi",
        category = TicketCategory.KONEKSI_PUTUS,
        subject = subject,
        description = "Lampu LOS merah, sudah restart modem tapi tetap mati.",
        at = at,
    )

    @Test
    fun `tiket baru lahir terbuka dengan kode yang bisa disebut lewat telepon`() {
        val t = ticket()

        assertThat(t.status).isEqualTo(TicketStatus.OPEN)
        assertThat(t.code).startsWith("TKT-").hasSize(12)
        assertThat(t.workOrderCode).isNull()
        // Laporan awal tersimpan di tiketnya sendiri, jadi utas belum berisi apa pun.
        assertThat(t.pendingMessages()).isEmpty()
        assertThat(t.lastActivityAt).isEqualTo(at)
    }

    @Test
    fun `laporan tanpa isi ditolak sebelum sempat mengotori antrean`() {
        assertThatThrownBy { ticket(subject = "   ") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Judul")
    }

    @Test
    fun `balasan operator mengangkat tiket dari antrean ke sedang ditangani`() {
        val t = ticket()

        t.replyByOperator(operatorId, "Rina", "Sedang kami cek dari sisi jaringan.", at.plusSeconds(60))

        assertThat(t.status).isEqualTo(TicketStatus.IN_PROGRESS)
        assertThat(t.pendingMessages()).singleElement().satisfies({
            assertThat(it.author).isEqualTo(TicketAuthor.OPERATOR)
            assertThat(it.authorName).isEqualTo("Rina")
        })
        assertThat(t.lastActivityAt).isEqualTo(at.plusSeconds(60))
    }

    @Test
    fun `pelanggan membalas tiket yang dinyatakan selesai membukanya kembali`() {
        val t = ticket()
        t.changeStatus(TicketStatus.RESOLVED, operatorId, "Rina", at.plusSeconds(60))
        t.clearPendingMessages()

        t.replyByCustomer("Masih mati juga, Pak.", at.plusSeconds(120))

        assertThat(t.status).isEqualTo(TicketStatus.OPEN)
        assertThat(t.resolvedAt).isNull()
        // Balasan pelanggan + jejak sistem yang menerangkan kenapa tiketnya hidup lagi.
        assertThat(t.pendingMessages().map { it.author })
            .containsExactly(TicketAuthor.CUSTOMER, TicketAuthor.SYSTEM)
    }

    @Test
    fun `tiket yang ditutup benar-benar berhenti menerima balasan`() {
        val t = ticket()
        t.closeByCustomer(at.plusSeconds(60))

        assertThat(t.status).isEqualTo(TicketStatus.CLOSED)
        assertThat(t.closedAt).isEqualTo(at.plusSeconds(60))
        assertThatThrownBy { t.replyByCustomer("halo?", at.plusSeconds(120)) }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { t.replyByOperator(operatorId, "Rina", "halo?", at.plusSeconds(120)) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `tiket yang sudah ditutup tak bisa dipindah ke status mana pun`() {
        val t = ticket()
        t.changeStatus(TicketStatus.CLOSED, operatorId, "Rina", at.plusSeconds(60))

        assertThatThrownBy { t.changeStatus(TicketStatus.OPEN, operatorId, "Rina", at.plusSeconds(120)) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("CLOSED")
    }

    @Test
    fun `eskalasi menautkan work order sekali saja`() {
        val t = ticket()
        val woId = UUID.randomUUID()

        t.attachWorkOrder(woId, "WO-ABCD1234", operatorId, "Rina", at.plusSeconds(60))

        assertThat(t.workOrderId).isEqualTo(woId)
        assertThat(t.workOrderCode).isEqualTo("WO-ABCD1234")
        assertThat(t.status).isEqualTo(TicketStatus.IN_PROGRESS)
        assertThat(t.pendingMessages()).singleElement().satisfies({
            assertThat(it.author).isEqualTo(TicketAuthor.SYSTEM)
            assertThat(it.body).contains("WO-ABCD1234")
        })

        assertThatThrownBy {
            t.attachWorkOrder(UUID.randomUUID(), "WO-99999999", operatorId, "Rina", at.plusSeconds(120))
        }.isInstanceOf(ConflictException::class.java).hasMessageContaining("WO-ABCD1234")
    }

    @Test
    fun `pesan kosong tak pernah masuk utas`() {
        val t = ticket()

        assertThatThrownBy { t.replyByCustomer("   ", at.plusSeconds(60)) }
            .isInstanceOf(ValidationException::class.java)
        assertThat(t.pendingMessages()).isEmpty()
    }

    @Test
    fun `tiket baru lahir dengan dua tenggat sesuai prioritas normalnya`() {
        val t = ticket()

        assertThat(t.priority).isEqualTo(TicketPriority.NORMAL)
        assertThat(t.responseDueAt).isEqualTo(at.plus(Duration.ofHours(4)))
        assertThat(t.resolutionDueAt).isEqualTo(at.plus(Duration.ofHours(24)))
        assertThat(t.slaOverdue(at.plusSeconds(60))).isFalse()
    }

    @Test
    fun `tenggat balasan yang lewat menandai tiket telat`() {
        val t = ticket()

        val lewat = at.plus(Duration.ofHours(5))
        assertThat(t.responseOverdue(lewat)).isTrue()
        assertThat(t.resolutionOverdue(lewat)).isFalse()
    }

    @Test
    fun `balasan operator menghentikan jam balasan, bukan jam penyelesaian`() {
        val t = ticket()

        t.replyByOperator(operatorId, "Rina", "Sedang kami cek.", at.plusSeconds(60))

        assertThat(t.responseDueAt).isNull()
        assertThat(t.firstResponseAt).isEqualTo(at.plusSeconds(60))
        // Sudah dibalas, tapi belum tuntas: lewat 24 jam tetap terhitung telat selesai.
        assertThat(t.responseOverdue(at.plus(Duration.ofHours(25)))).isFalse()
        assertThat(t.resolutionOverdue(at.plus(Duration.ofHours(25)))).isTrue()
    }

    @Test
    fun `pelanggan yang membalas lagi memulai ulang jam balasan`() {
        val t = ticket()
        t.replyByOperator(operatorId, "Rina", "Sudah kami cek, coba restart.", at.plusSeconds(60))

        val balasan = at.plus(Duration.ofHours(2))
        t.replyByCustomer("Masih mati.", balasan)

        // Bukan "tenggat balasan pertama": pelanggan yang menunggu lagi harus terlihat menunggu.
        assertThat(t.responseDueAt).isEqualTo(balasan.plus(Duration.ofHours(4)))
        assertThat(t.firstResponseAt).isEqualTo(at.plusSeconds(60))
    }

    @Test
    fun `menaikkan prioritas memperpendek sisa waktu, bukan memperpanjangnya`() {
        val t = ticket()

        t.changePriority(TicketPriority.URGENT, at.plus(Duration.ofHours(3)))

        // Jam mulainya dipertahankan: 4 jam sejak dibuka jadi 30 menit sejak dibuka —
        // tiket yang sudah 3 jam menganggur langsung terlihat telat, bukan dapat waktu baru.
        assertThat(t.responseDueAt).isEqualTo(at.plus(Duration.ofMinutes(30)))
        assertThat(t.resolutionDueAt).isEqualTo(at.plus(Duration.ofHours(4)))
        assertThat(t.responseOverdue(at.plus(Duration.ofHours(3)))).isTrue()
    }

    @Test
    fun `tiket yang dibuka kembali mendapat ronde tenggat yang baru`() {
        val t = ticket()
        t.changeStatus(TicketStatus.RESOLVED, operatorId, "Rina", at.plusSeconds(60))

        val dibukaLagi = at.plus(Duration.ofDays(2))
        t.changeStatus(TicketStatus.OPEN, operatorId, "Rina", dibukaLagi)

        assertThat(t.responseDueAt).isEqualTo(dibukaLagi.plus(Duration.ofHours(4)))
        assertThat(t.resolutionDueAt).isEqualTo(dibukaLagi.plus(Duration.ofHours(24)))
        assertThat(t.slaOverdue(dibukaLagi)).isFalse()
    }

    @Test
    fun `penugasan tak mengubah status dan tak menulis apa pun ke utas`() {
        val t = ticket()

        t.assignTo(operatorId, "Rina", at.plusSeconds(60))

        assertThat(t.assigneeId).isEqualTo(operatorId)
        assertThat(t.assigneeName).isEqualTo("Rina")
        assertThat(t.status).isEqualTo(TicketStatus.OPEN)
        // Utasnya dibaca pelanggan; pembagian kerja internal bukan urusan mereka.
        assertThat(t.pendingMessages()).isEmpty()

        t.assignTo(null, null, at.plusSeconds(120))
        assertThat(t.assigneeId).isNull()
        assertThat(t.assigneeName).isNull()
    }

    @Test
    fun `penanda peringatan SLA dibersihkan begitu tiketnya bergerak`() {
        val t = ticket()
        t.markSlaAlerted(at.plus(Duration.ofHours(5)))
        assertThat(t.slaAlertedAt).isNotNull()

        t.replyByOperator(operatorId, "Rina", "Maaf terlambat, sedang kami cek.", at.plus(Duration.ofHours(6)))

        // Kalau penandanya dibiarkan, pelanggaran tenggat penyelesaian nanti tak akan
        // pernah diteriakkan lagi.
        assertThat(t.slaAlertedAt).isNull()
    }
}
