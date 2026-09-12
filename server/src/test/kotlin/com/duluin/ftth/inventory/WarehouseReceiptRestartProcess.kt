package com.duluin.ftth.inventory

import com.duluin.ftth.FtthApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import java.nio.file.Files
import java.nio.file.Path

object WarehouseReceiptRestartProcess {
    @JvmStatic fun main(args: Array<String>) {
        val context = SpringApplicationBuilder(FtthApplication::class.java).profiles("test").run(
            "--server.address=127.0.0.1", "--server.port=0", "--ftth.bootstrap.seed-demo-tenant=false",
            "--spring.datasource.hikari.data-source-properties.ApplicationName=${args[1]}",
            "--spring.datasource.url=${args[2]}", "--spring.flyway.url=${args[2]}",
            "--spring.flyway.schemas=${args[3]}", "--spring.flyway.default-schema=${args[3]}",
        )
        context.getBean(javax.sql.DataSource::class.java).connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_database(),current_user,current_schema(),rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user").use { rows ->
                check(rows.next() && rows.getString(1) == "warehouse_test" && rows.getString(2) == "warehouse_app" && rows.getString(3) == args[3])
                check(!rows.getBoolean(4) && !rows.getBoolean(5))
            }
        } }
        ChildProcessPort.publish(Path.of(args[0]), requireNotNull((context as WebServerApplicationContext).webServer).port)
    }
}
