package com.duluin.ftth

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.Modulithic
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Scheduling diaktifkan untuk penjaga collector membisu: deteksi gangguan lain
 * bergantung pada data yang masuk, sehingga hanya pemeriksaan berkala yang bisa
 * menyadari ketiadaan data.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(sharedModules = ["common"])
class FtthApplication

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "ftth.scheduling",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableScheduling
class SchedulingConfiguration

fun main(args: Array<String>) {
    SpringApplication.run(FtthApplication::class.java, *args)
}
