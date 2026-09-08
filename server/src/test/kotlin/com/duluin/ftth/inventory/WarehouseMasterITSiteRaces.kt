package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseMasterITSiteRaces : WarehouseMasterHttpFixture() {
    @ParameterizedTest
    @CsvSource("SITE,CREATE,MOVE", "SITE,CREATE,DELETE", "SITE,UPDATE,MOVE", "SITE,UPDATE,DELETE",
        "LOCATION,CREATE,MOVE", "LOCATION,CREATE,DELETE", "LOCATION,UPDATE,MOVE", "LOCATION,UPDATE,DELETE")
    fun `AV7-02 site and location races serialize without sleeps`(winner: String, action: String, change: String) {
        val token = tenant(); val fixture = fixture(token)
        fun siteBody(area: String) = """{"code":"SITE","name":"Site","location":{"longitude":106.8,"latitude":-6.2},"areaId":"$area"}"""
        val siteResponse = request("POST", "/api/sites", token, siteBody(area(token)))
        assertThat(siteResponse.status).isEqualTo(201)
        val site = mapper.readTree(siteResponse.contentAsString).path("id").asString()
        val other = mapper.readTree(request("POST", "/api/areas", token,"""{"code":"OTHER","name":"Other"}""").contentAsString).path("id").asString()
        val id = if (action == "UPDATE") create("locations",token,"""{"code":"WH","name":"Warehouse","kind":"WAREHOUSE"}""").path("id").asString() else UUID.randomUUID().toString()
        val executor = Executors.newSingleThreadExecutor()
        try {
            DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD")).use { connection ->
                connection.autoCommit = false
                execute(connection,"SET LOCAL app.tenant_id='${fixture.tenant}'")
                val pid = scalar(connection,"SELECT pg_backend_pid()")
                if (winner == "SITE") {
                    execute(connection, if (change == "DELETE") "DELETE FROM site WHERE id='$site'" else "UPDATE site SET area_id='$other' WHERE id='$site'")
                } else if (action == "CREATE") {
                    execute(connection,"INSERT INTO inventory_location(id,tenant_id,code,name,kind,site_id,area_id) VALUES ('$id','${fixture.tenant}','WH','Warehouse','WAREHOUSE','$site','${area(token)}')")
                } else execute(connection,"UPDATE inventory_location SET site_id='$site',revision=revision+1 WHERE id='$id'")
                val started = java.util.concurrent.CountDownLatch(1)
                val future = executor.submit<Int> {
                    started.countDown()
                    if (winner == "SITE") {
                        val body = """{"code":"WH","name":"Warehouse","kind":"WAREHOUSE","areaId":"${area(token)}","siteId":"$site"""" +
                            (if (action == "UPDATE") ",\"expectedRevision\":0}" else "}")
                        request(if (action == "CREATE") "POST" else "PUT", "/api/v1/warehouse/locations" + if (action=="CREATE") "" else "/$id", token,body).status
                    } else request(if(change=="DELETE") "DELETE" else "PUT", "/api/sites/$site",token,if(change=="DELETE") null else siteBody(other)).status
                }
                check(started.await(10,TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (scalar(connection,"SELECT count(*) FROM pg_stat_activity WHERE $pid=ANY(pg_blocking_pids(pid))")=="0") {
                    check(!future.isDone && System.nanoTime()<deadline) { "contender did not wait on the site reference lock" }
                    Thread.onSpinWait()
                }
                connection.commit()
                val status = future.get(20,TimeUnit.SECONDS)
                println("AV7-02 race $winner/$action/$change blocked=true status=$status")
                assertThat(status).isEqualTo(if(winner=="SITE") 404 else 409)
            }
        } finally { executor.shutdownNow(); executor.awaitTermination(10,TimeUnit.SECONDS) }
        fixture.transaction {
            val dangling = scalar("SELECT count(*) FROM inventory_location location LEFT JOIN site ON site.tenant_id=location.tenant_id AND site.id=location.site_id WHERE location.site_id IS NOT NULL AND (site.id IS NULL OR site.area_id IS DISTINCT FROM location.area_id)")
            assertThat(dangling).isEqualTo("0")
        }
    }
    private fun execute(connection: Connection, sql: String) { connection.createStatement().use { it.execute(sql) } }
    private fun scalar(connection: Connection, sql: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
}
