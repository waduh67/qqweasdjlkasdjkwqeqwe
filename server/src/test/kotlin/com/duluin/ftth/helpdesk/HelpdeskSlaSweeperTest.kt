package com.duluin.ftth.helpdesk

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.helpdesk.application.port.inbound.TicketFilter
import com.duluin.ftth.helpdesk.application.port.outbound.TicketRepository
import com.duluin.ftth.helpdesk.application.service.HelpdeskSlaSweeper
import com.duluin.ftth.helpdesk.domain.model.Ticket
import com.duluin.ftth.helpdesk.domain.model.TicketCategory
import com.duluin.ftth.helpdesk.domain.model.TicketMessage
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Menguji penjaga SLA helpdesk dengan fake murni (tanpa Spring/DB), memakai
 * [HelpdeskSlaSweeper.sweep] berjam tetap agar bebas dari jam dinding.
 *
 * Yang dijaga: satu pelanggaran = satu teriakan (bukan satu tiap lima menit), dan
 * jenis pelanggarannya benar — karena "belum dibalas sama sekali" dan "sudah dibalas
 * tapi belum tuntas" menuntut tindakan yang berbeda dari yang menerimanya.
 */
class HelpdeskSlaSweeperTest {

    private val tenantId: UUID = UUID.randomUUID()
    private val openedAt: Instant = Instant.parse("2026-08-09T02:00:00Z")

    private fun ticket() = Ticket.open(
        tenantId = tenantId,
        customerId = UUID.randomUUID(),
        customerName = "Budi",
        category = TicketCategory.KONEKSI_PUTUS,
        subject = "Internet mati sejak pagi",
        description = "Lampu LOS merah.",
        at = openedAt,
    )

    @Test
    fun `tiket telat balas diteriakkan sekali lalu ditandai`() {
        val t = ticket()
        val repo = FakeTicketRepository(listOf(t))
        val events = RecordingPublisher()
        val sweeper = HelpdeskSlaSweeper(repo, events)

        val now = openedAt.plus(Duration.ofHours(5))
        sweeper.sweep(now)

        assertThat(events.published).singleElement().satisfies({
            val e = it as TicketSlaBreached
            assertThat(e.ticketId).isEqualTo(t.id)
            assertThat(e.overdueKind).isEqualTo("RESPONSE")
            assertThat(e.dueAt).isEqualTo(openedAt.plus(Duration.ofHours(4)))
            assertThat(e.assigneeId).isNull()
        })
        assertThat(t.slaAlertedAt).isEqualTo(now)
        assertThat(repo.saved).contains(t)
    }

    @Test
    fun `ronde berikutnya diam karena tiket yang sudah ditandai tak terambil lagi`() {
        val t = ticket()
        val repo = FakeTicketRepository(listOf(t))
        val events = RecordingPublisher()
        val sweeper = HelpdeskSlaSweeper(repo, events)

        sweeper.sweep(openedAt.plus(Duration.ofHours(5)))
        sweeper.sweep(openedAt.plus(Duration.ofHours(6)))

        // Peringatan yang berulang tiap lima menit berakhir dimatikan orang, bukan ditindak.
        assertThat(events.published).hasSize(1)
    }

    @Test
    fun `tiket yang sudah dibalas tapi belum tuntas dilaporkan sebagai telat selesai`() {
        val t = ticket()
        t.replyByOperator(UUID.randomUUID(), "Rina", "Sedang kami cek.", openedAt.plusSeconds(60))
        val repo = FakeTicketRepository(listOf(t))
        val events = RecordingPublisher()
        val sweeper = HelpdeskSlaSweeper(repo, events)

        sweeper.sweep(openedAt.plus(Duration.ofHours(25)))

        assertThat(events.published).singleElement().satisfies({
            val e = it as TicketSlaBreached
            assertThat(e.overdueKind).isEqualTo("RESOLUTION")
            assertThat(e.dueAt).isEqualTo(openedAt.plus(Duration.ofHours(24)))
        })
    }

    @Test
    fun `antrean yang sehat tak menerbitkan apa pun`() {
        val repo = FakeTicketRepository(listOf(ticket()))
        val events = RecordingPublisher()

        HelpdeskSlaSweeper(repo, events).sweep(openedAt.plus(Duration.ofHours(1)))

        assertThat(events.published).isEmpty()
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)
        override fun publishEvent(event: Any) {
            published += event
        }
    }

    /**
     * Menirukan penyaringan SQL adapter di memori: hanya tiket hidup yang salah satu
     * tenggatnya lewat, dan — bila diminta — yang belum pernah ditandai.
     */
    private class FakeTicketRepository(private val all: List<Ticket>) : TicketRepository {
        val saved = mutableListOf<Ticket>()

        override fun save(ticket: Ticket): Ticket {
            saved += ticket
            return ticket
        }

        override fun findOverdue(now: Instant, onlyUnalerted: Boolean): List<Ticket> =
            all.filter { it.slaOverdue(now) && (!onlyUnalerted || it.slaAlertedAt == null) }

        override fun findById(id: UUID): Ticket? = all.find { it.id == id }
        override fun findByCustomer(customerId: UUID): List<Ticket> = emptyList()
        override fun search(filter: TicketFilter, pageRequest: PageRequest): Page<Ticket> =
            Page(emptyList(), 0, 0, 0)

        override fun messagesOf(ticketId: UUID): List<TicketMessage> = emptyList()
        override fun countByStatus(): Map<TicketStatus, Long> = emptyMap()
        override fun countOpenOf(customerId: UUID): Long = 0
        override fun countUnassigned(): Long = 0
        override fun countOverdue(now: Instant): Long = findOverdue(now).size.toLong()
    }
}
