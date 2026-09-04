package com.duluin.ftth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource

class SchedulingTestConfigurationTest {
    @Test
    fun `Gradle test JVM disables scheduling before Spring context refresh`() {
        assertThat(System.getProperty("ftth.scheduling.enabled")).isEqualTo("false")

        val context = contextWithScheduling("false")

        assertThat(context.getBeansOfType(SchedulingConfiguration::class.java)).isEmpty()
        context.close()
    }

    @Test
    fun `explicit scheduling opt in registers scheduling configuration`() {
        val context = contextWithScheduling("true")

        assertThat(context.getBeansOfType(SchedulingConfiguration::class.java)).hasSize(1)
        context.close()
    }

    @Test
    fun `test profile disables scheduling unless a test opts in explicitly`() {
        val factory = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-test.yml"))
        }

        assertThat(factory.`object`?.getProperty("ftth.scheduling.enabled")).isEqualTo("false")
        assertThat(SchedulingConfiguration::class.java.isAnnotationPresent(EnableScheduling::class.java)).isTrue()
    }

    private fun contextWithScheduling(enabled: String): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(
            MapPropertySource("test", mapOf("ftth.scheduling.enabled" to enabled)),
        )
        context.register(SchedulingConfiguration::class.java)
        context.refresh()
        return context
    }
}
