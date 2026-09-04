package com.duluin.ftth

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.Ordered
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration

class SchedulingDisabledContextCustomizerFactory : ContextCustomizerFactory {
    override fun createContextCustomizer(
        testClass: Class<*>,
        configAttributes: List<ContextConfigurationAttributes>,
    ): ContextCustomizer = SchedulingDisabledContextCustomizer

    private object SchedulingDisabledContextCustomizer : ContextCustomizer {
        override fun customizeContext(
            context: ConfigurableApplicationContext,
            mergedConfig: MergedContextConfiguration,
        ) {
            if (context.environment.getProperty("ftth.scheduling.enabled") != "true") {
                context.addBeanFactoryPostProcessor(SchedulingBeanDefinitionRemover())
            }
        }
    }

    private class SchedulingBeanDefinitionRemover : BeanFactoryPostProcessor, Ordered {
        override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
            val registry = beanFactory as? BeanDefinitionRegistry ?: return
            if (registry.containsBeanDefinition(SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
                registry.removeBeanDefinition(SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
            }
        }

        override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
    }

    private companion object {
        const val SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME =
            "org.springframework.scheduling.config.internalScheduledAnnotationProcessor"
    }
}
