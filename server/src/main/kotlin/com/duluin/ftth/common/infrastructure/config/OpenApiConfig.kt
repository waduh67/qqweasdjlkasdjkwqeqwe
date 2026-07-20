package com.duluin.ftth.common.infrastructure.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.annotations.servers.Server
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(title = "FTTH OSS API", version = "0.1.0", description = "Manajemen infrastruktur FTTH multi-tenant"),
    servers = [Server(url = "/", description = "Server ini")],
)
@SecurityScheme(
    name = "bearer-jwt",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
)
class OpenApiConfig
