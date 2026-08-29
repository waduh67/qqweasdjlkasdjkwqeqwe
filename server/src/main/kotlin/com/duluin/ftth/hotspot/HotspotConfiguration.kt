package com.duluin.ftth.hotspot

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class HotspotConfiguration {
    @Bean
    fun hotspotClock(): Clock = Clock.systemUTC()
}
