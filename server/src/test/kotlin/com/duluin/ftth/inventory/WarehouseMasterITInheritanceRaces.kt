package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseMasterITInheritanceRaces : WarehouseInheritanceFixture() {
    @ParameterizedTest @CsvSource("SITE,SQL", "REPARENT,SQL", "SITE,HTTP", "REPARENT,HTTP")
    fun `topology fence serializes site change and transitive subtree reparent`(winner: String, contender: String) {
        val token=tenant(); val first=site(token,"A"); val second=site(token,"B")
        val root=location(token,"ROOT",null,null); val bridge=location(token,"BRIDGE",root,null)
        val source=location(token,"SOURCE",null,null); location(token,"LEAF",source,second)
        val fixture=fixture(token)
        val siteChange="UPDATE inventory_location SET site_id='$first',revision=revision+1 WHERE id='$root'"
        val reparent="UPDATE inventory_location SET parent_location_id='$bridge',revision=revision+1 WHERE id='$source'"
        val executor=Executors.newSingleThreadExecutor()
        try {
            open(fixture.tenant).use { connection ->
                val pid=scalar(connection,"SELECT pg_backend_pid()")
                execute(connection,if(winner=="SITE") siteChange else reparent)
                val started=CountDownLatch(1)
                val future=executor.submit<String> {
                    if(contender=="SQL") open(fixture.tenant).use { competing ->
                        started.countDown()
                        try { execute(competing,if(winner=="SITE") reparent else siteChange); competing.commit(); "COMMITTED" }
                        catch(error: SQLException) { competing.rollback(); error.sqlState }
                    } else {
                        started.countDown()
                        val id=if(winner=="SITE") source else root
                        val body=if(winner=="SITE") locationBody(token,"SOURCE",bridge,null) else locationBody(token,"ROOT",null,first)
                        request("PUT","/api/v1/warehouse/locations/$id",token,body.dropLast(1)+",\"expectedRevision\":0}").status.toString()
                    }
                }
                check(started.await(10,TimeUnit.SECONDS))
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
                while(scalar(connection,"SELECT count(*) FROM pg_stat_activity WHERE $pid=ANY(pg_blocking_pids(pid))")=="0") {
                    check(!future.isDone && System.nanoTime()<deadline) { "contender did not block on topology fence" }
                    Thread.onSpinWait()
                }
                connection.commit()
                val outcome=future.get(20,TimeUnit.SECONDS)
                println("INHERITANCE RACE winner=$winner contender=$contender blocked=true outcome=$outcome")
                assertThat(outcome).isEqualTo(if(contender=="SQL") "23514" else "409")
            }
        } finally { executor.shutdownNow(); executor.awaitTermination(10,TimeUnit.SECONDS) }
        fixture.transaction {
            val conflicts=scalar("WITH RECURSIVE chain AS (SELECT id AS origin,id,parent_location_id,site_id FROM inventory_location UNION ALL SELECT child.origin,parent.id,parent.parent_location_id,parent.site_id FROM chain child JOIN inventory_location parent ON child.parent_location_id=parent.id) SELECT count(*) FROM (SELECT origin FROM chain GROUP BY origin HAVING count(DISTINCT site_id)>1) invalid")
            assertThat(conflicts).isEqualTo("0")
        }
    }
    private fun open(tenant: UUID): Connection = DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD")).apply {
        autoCommit=false; execute(this,"SET LOCAL app.tenant_id='$tenant'")
    }
    private fun execute(connection: Connection, sql: String) { connection.createStatement().use { it.execute(sql) } }
    private fun scalar(connection: Connection, sql: String): String = connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) } }
}
