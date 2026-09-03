package com.duluin.ftth.provisioning.config

import com.duluin.ftth.provisioning.application.service.ExecutionPolicy
import com.duluin.ftth.provisioning.application.service.RetrySleeper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.locks.LockSupport

@Configuration
class ProvisioningRuntimeConfiguration {
    @Bean
    fun provisioningExecutionPolicy() = ExecutionPolicy()

    @Bean
    fun provisioningRetrySleeper() = RetrySleeper { duration -> LockSupport.parkNanos(duration.toNanos()) }
}
