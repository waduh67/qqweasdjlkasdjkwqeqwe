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
        )
        Files.writeString(Path.of(args[0]), requireNotNull((context as WebServerApplicationContext).webServer).port.toString())
    }
}
