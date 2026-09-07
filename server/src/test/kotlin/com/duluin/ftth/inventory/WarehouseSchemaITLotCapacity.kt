package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.WarehouseLotCapacityFixture.Companion.sql
import com.duluin.ftth.inventory.WarehouseLotCapacityFixture.Companion.value
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WarehouseSchemaITLotCapacity {
    @Test
    fun `V174_2 upgrade retains allocations and migration validation reruns without changes`() {
        WarehouseSchemaDatabase("174.2").use { database ->
            val stock=WarehouseLotCapacityFixture(database)
            stock.create(40)
            assertThat(database.migrate("174.3").migrationsExecuted).isEqualTo(1)
            assertThat(stock.totals()).containsExactly(40L,40L,40L)
            stock.open().use { connection -> stock.addPiece(connection,60); connection.commit() }
            assertThat(database.migrate("174.3").migrationsExecuted).isZero()
            assertThat(stock.totals()).containsExactly(100L,100L,100L)
        }
    }

    @Test
    fun `second independent root cannot double available and reserved lot quantity`() = fixture(100) { stock ->
        val failure = runCatching {
            stock.open().use { connection -> stock.addPiece(connection,100); connection.commit() }
        }.exceptionOrNull()
        val totals = stock.totals()
        println("lot100 root100+100 commit=${(failure as? SQLException)?.sqlState ?: "00000"} roots=${totals[0]} physical=${totals[1]} reserved=${totals[2]}")
        assertThat(failure).isInstanceOf(SQLException::class.java)
        assertThat((failure as SQLException).sqlState).isEqualTo("23514")
        assertThat(totals).containsExactly(100L,100L,100L)
    }

    @Test
    fun `roots forty plus sixty fill exactly one hundred and reject one more`() = fixture(40) { stock ->
        stock.open().use { connection -> stock.addPiece(connection,60); connection.commit() }
        assertThat(stock.totals()).containsExactly(100L,100L,100L)
        stock.open().use { connection ->
            stock.addPiece(connection,1)
            assertThat(assertThrows<SQLException> { connection.commit() }.sqlState).isEqualTo("23514")
        }
        assertThat(stock.totals()).containsExactly(100L,100L,100L)
    }

    @Test
    fun `roots forty plus sixty one fail at commit`() = fixture(40) { stock ->
        stock.open().use { connection ->
            stock.addPiece(connection,61)
            assertThat(assertThrows<SQLException> { connection.commit() }.sqlState).isEqualTo("23514")
        }
        assertThat(stock.totals()).containsExactly(40L,40L,40L)
    }

    @Test
    fun `several roots in one transaction use final aggregate without counting descendants`() = fixture(20) { stock ->
        stock.open().use { connection ->
            stock.addPiece(connection,40)
            stock.addPiece(connection,40)
            connection.commit()
        }
        assertThat(stock.totals()).containsExactly(100L,100L,100L)
    }

    @ParameterizedTest
    @ValueSource(strings=["REPEATABLE READ","SERIALIZABLE"])
    fun `stale snapshot isolation cannot bypass locked allocation`(isolation: String) = fixture(20) { stock ->
        stock.database.dataSource.connection.use { connection ->
            connection.autoCommit=false
            connection.sql("SET TRANSACTION ISOLATION LEVEL $isolation")
            connection.sql("SET LOCAL app.tenant_id='${stock.tenant}'")
            assertThat(assertThrows<SQLException> { stock.addPiece(connection,60); connection.commit() }.sqlState).isEqualTo("40001")
        }
        assertThat(stock.totals()).containsExactly(20L,20L,20L)
    }

    @ParameterizedTest
    @ValueSource(strings=["SPLIT","RETIRED"])
    fun `terminal root retains allocated capacity and cannot be deleted`(state: String) = fixture(100) { stock ->
        stock.retireOrSplit(state)
        assertThat(stock.totals()[0]).isEqualTo(100L)
        stock.open().use { connection ->
            stock.addPiece(connection,1)
            assertThat(assertThrows<SQLException> { connection.commit() }.sqlState).isEqualTo("23514")
        }
        stock.open().use { connection ->
            assertThat(assertThrows<SQLException> { connection.sql("DELETE FROM inventory_segment WHERE id='${stock.firstRoot}'") }.sqlState).isEqualTo("23514")
        }
        if (state=="SPLIT") assertThat(stock.totals()).containsExactly(100L,100L,100L)
    }

    @Test
    fun `concurrent root admissions serialize on lot and only one exceeding contender commits`() = fixture(20) { stock ->
        val firstInserted=CountDownLatch(1)
        val releaseFirst=CountDownLatch(1)
        val secondStarted=CountDownLatch(1)
        val secondPid=AtomicInteger()
        val workers=Executors.newFixedThreadPool(2)
        try {
            val first=workers.submit<String> {
                stock.open().use { connection ->
                    stock.addPiece(connection,60)
                    firstInserted.countDown()
                    check(releaseFirst.await(15,TimeUnit.SECONDS))
                    connection.commit()
                    "00000"
                }
            }
            check(firstInserted.await(15,TimeUnit.SECONDS))
            val second=workers.submit<String> {
                stock.open().use { connection ->
                    secondPid.set(connection.value("SELECT pg_backend_pid()").toInt())
                    secondStarted.countDown()
                    try {
                        stock.addPiece(connection,60)
                        connection.commit()
                        "00000"
                    } catch (failure: SQLException) {
                        connection.rollback()
                        failure.sqlState
                    }
                }
            }
            check(secondStarted.await(15,TimeUnit.SECONDS))
            var observedLock=false
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            stock.database.dataSource.connection.use { observer ->
                while (!second.isDone && System.nanoTime()<deadline) {
                    observedLock=observer.value("SELECT coalesce((SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=${secondPid.get()}),false)")=="t"
                    if (observedLock) break
                    Thread.onSpinWait()
                }
            }
            releaseFirst.countDown()
            val outcomes=listOf(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))
            val totals=stock.totals()
            println("lot race observedLock=$observedLock outcomes=$outcomes roots=${totals[0]} physical=${totals[1]} reserved=${totals[2]}")
            assertThat(observedLock).isTrue()
            assertThat(outcomes).containsExactly("00000","23514")
            assertThat(totals).containsExactly(80L,80L,80L)
        } finally {
            releaseFirst.countDown()
            workers.shutdownNow()
            check(workers.awaitTermination(25,TimeUnit.SECONDS))
        }
    }

    private fun fixture(initial: Long, action: (WarehouseLotCapacityFixture) -> Unit) {
        WarehouseSchemaDatabase().use { database ->
            val stock=WarehouseLotCapacityFixture(database)
            stock.create(initial)
            action(stock)
        }
    }
}
