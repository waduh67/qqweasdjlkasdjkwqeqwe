package com.duluin.ftth

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.modulith.Modulithic

@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(sharedModules = ["common"])
class FtthApplication

fun main(args: Array<String>) {
    SpringApplication.run(FtthApplication::class.java, *args)
}
