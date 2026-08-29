package com.duluin.ftth.hotspot

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ftth.hotspot.portal-context")
data class PortalContextProperties(
    val ttl: Duration = Duration.ofMinutes(5),
    val allowedRedirectHosts: Set<String> = emptySet(),
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "portal context TTL harus > 0" }
    }
}
