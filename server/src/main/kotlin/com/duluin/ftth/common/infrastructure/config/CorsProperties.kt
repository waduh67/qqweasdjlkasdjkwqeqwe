package com.duluin.ftth.common.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ftth.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = listOf("http://localhost:5173"),
)
