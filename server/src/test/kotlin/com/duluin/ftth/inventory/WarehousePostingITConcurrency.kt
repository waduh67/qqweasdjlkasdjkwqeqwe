package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehousePostingITConcurrency {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }

    @Test fun `last serial races commit one physical move`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        val outcomes=race(fixture) {
            post(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1")))
        }
        assertThat(outcomes.count { it }).isEqualTo(1)
        fixture.transaction { assertThat(total(warehouse,StockUnit.EA)).isZero(); assertThat(total(technician,StockUnit.EA)).isEqualTo(1) }
    }

    @Test fun `missing zero dimension creation races retain both legal bulk moves`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.each("100"),bulk=true) }
        val commands=(1..2).map { fixture.transaction { move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("50")) } }
        val index=java.util.concurrent.atomic.AtomicInteger()
        assertThat(race(fixture) { post(commands[index.getAndIncrement()]) }).containsOnly(true)
        fixture.transaction {
            assertThat(total(warehouse,StockUnit.EA)).isZero(); assertThat(total(technician,StockUnit.EA)).isEqualTo(100)
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE location_id='$technician'")).isEqualTo("1")
        }
    }

    @Test fun `same cable split races retire parent exactly once`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.metres("100")) }
        val outcomes=race(fixture) {
            val cut=piece.copy(stockIdentityId=UUID.randomUUID(),locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            val rest=piece.copy(stockIdentityId=UUID.randomUUID())
            post(move(piece,cut,StockQuantity.metres("40"),splits=listOf(PostingSplit(piece.stockIdentityId,0,listOf(
                SegmentChild(cut.stockIdentityId,StockQuantity.metres("40"),SegmentKind.CUT),SegmentChild(rest.stockIdentityId,StockQuantity.metres("60"),SegmentKind.REMNANT)))),
                extra=listOf(rest to StockQuantity.metres("60"))))
        }
        assertThat(outcomes.count { it }).isEqualTo(1)
        fixture.transaction {
            assertThat(total(warehouse)).isEqualTo(60000); assertThat(total(technician)).isEqualTo(40000)
            assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE parent_segment_id='${piece.stockIdentityId}'")).isEqualTo("2")
        }
    }

    @Test fun `opposite input orders acquire the same multidimension lock order`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val pieces=fixture.transaction { listOf(receipt(StockQuantity.each("100"),true),receipt(StockQuantity.each("100"),true)) }
        val counter=java.util.concurrent.atomic.AtomicInteger()
        val postings=(1..2).map { fixture.transaction {
            val commands=pieces.map { piece -> move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("50")) }
            val first=commands.first()
            val second=commands.last()
            val secondLine=UUID.randomUUID()
            sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) SELECT '$secondLine',tenant_id,'${first.documentId}',2,0,sku_id,stock_identity_id,lot_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner FROM inventory_document_line WHERE document_id='${second.documentId}'")
            val combined=first.copy(legs=first.legs+second.legs.map { it.copy(documentLineId=secondLine) })
            if(counter.incrementAndGet()==1) combined else combined.copy(legs=combined.legs.reversed())
        } }
        counter.set(0)
        val outcomes=race(fixture) { post(postings[counter.getAndIncrement()]) }
        assertThat(outcomes).containsOnly(true)
        fixture.transaction { assertThat(total(warehouse,StockUnit.EA)).isZero(); assertThat(total(technician,StockUnit.EA)).isEqualTo(200) }
    }

    @Test fun `projection rebuild waits for concurrent posting and loses no committed delta`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.metres("100")) }
        val reached=CountDownLatch(1)
        val release=CountDownLatch(1)
        val probe=PostingJdbcProbe(context,TestPostingPhase.LEGS) {
            reached.countDown(); check(release.await(15,TimeUnit.SECONDS))
        }
        val pool=Executors.newFixedThreadPool(2)
        try {
            val write=pool.submit<WarehousePostResult> { fixture.transaction { post(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.metres("100"))) } }
            check(reached.await(10,TimeUnit.SECONDS))
            val rebuild=pool.submit<List<PostingBalance>> { fixture.transaction { context.getBean(WarehousePosting::class.java).rebuild(0) } }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            var blocked=false
            while(!blocked && System.nanoTime()<deadline) {
                blocked=fixture.transaction { scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%inventory_tenant_cutover%'").toInt()>0 }
                Thread.yield()
            }
            assertThat(blocked).isTrue()
            release.countDown()
            write.get(15,TimeUnit.SECONDS)
            assertThat(rebuild.get(15,TimeUnit.SECONDS).single().quantity).isEqualTo(StockQuantity.metres("100"))
            fixture.transaction { assertThat(total(warehouse)).isZero(); assertThat(total(technician)).isEqualTo(100000) }
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS)
            probe.close()
        }
    }

    private fun race(fixture: WarehousePostingFixture, action: WarehousePostingFixture.() -> Unit): List<Boolean> {
        val pool=Executors.newFixedThreadPool(2)
        val start=CountDownLatch(1)
        return try {
            val futures=(1..2).map { pool.submit<Boolean> {
                check(start.await(10,TimeUnit.SECONDS))
                try { fixture.transaction { action() }; true } catch(failure: Exception) { System.out.println("POSTING_RACE_REJECT ${failure.javaClass.name}: ${failure.message}"); false }
            } }
            start.countDown(); futures.map { it.get(30,TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }
}
