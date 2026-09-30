package com.duluin.ftth.inventory

import com.duluin.ftth.FtthApplication
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.WarehouseReservationExpiry
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

object WarehouseReservationExpiryProcess {
    @JvmStatic fun main(args: Array<String>) {
        SpringApplicationBuilder(FtthApplication::class.java).profiles("test").run("--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=${args[1]}", "--spring.flyway.url=${args[1]}", "--spring.flyway.schemas=${args[2]}",
            "--spring.flyway.default-schema=${args[2]}", "--ftth.bootstrap.seed-demo-tenant=false").use { context ->
            val result = TenantContext.runAs(UUID.fromString(args[3])) { context.getBean(WarehouseReservationExpiry::class.java).expireOne() }
            Files.writeString(Path.of(args[0]), result.toString())
        }
    }
}
