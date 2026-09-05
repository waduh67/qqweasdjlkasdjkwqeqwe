package com.duluin.ftth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.ScheduledTaskHolder

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:postgresql://localhost:5432/ftth_test",
        "ftth.bootstrap.seed-demo-tenant=false",
    ],
)
class SchedulingSpringContextIT @Autowired constructor(
    private val environment: Environment,
    private val applicationContext: ApplicationContext,
) {
    @Test
    fun `ordinary Spring test context has scheduling disabled`() {
        assertThat(environment.getProperty("ftth.scheduling.enabled")).isEqualTo("false")
        assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
        assertThat(applicationContext.getBeansOfType(SchedulingConfiguration::class.java)).isEmpty()
        assertThat(
            applicationContext.getBeansOfType(ScheduledTaskHolder::class.java).values
                .flatMap { it.scheduledTasks },
        ).isEmpty()
    }
}
