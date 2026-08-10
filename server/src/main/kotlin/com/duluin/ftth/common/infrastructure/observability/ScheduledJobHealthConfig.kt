package com.duluin.ftth.common.infrastructure.observability

import com.duluin.ftth.common.infrastructure.config.ObservabilityProperties
import org.aopalliance.intercept.MethodInterceptor
import org.slf4j.LoggerFactory
import org.springframework.aop.Advisor
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.config.IntervalTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.stereotype.Component

/**
 * Memasang denyut nadi [JobHealthRegistry] ke SELURUH metode ber-`@Scheduled`, tanpa satu
 * baris pun berubah di penjadwal mana pun.
 *
 * Kenapa advisor AOP, bukan memanggil registry dari tiap job? Karena yang paling mahal
 * dari pemantauan adalah yang lupa dipasang. Dua belas penjadwal hari ini akan jadi lima
 * belas bulan depan, dan job yang paling mungkin macet justru yang paling sepi ditulis.
 * Dengan advisor, "terpantau" jadi sifat bawaan `@Scheduled` di aplikasi ini.
 *
 * Bukan `TaskDecorator` milik penjadwal: dekorator itu menerima `RunnableScheduledFuture`
 * yang identitas metodenya sudah hilang, dan pembungkus penanganan galat Spring berada di
 * dalamnya — jadi nama job maupun lemparannya tak akan pernah terlihat dari sana.
 */
@Configuration(proxyBeanMethods = false)
class ScheduledJobHealthConfig {

    @Bean
    fun jobHealthRegistry(properties: ObservabilityProperties): JobHealthRegistry =
        JobHealthRegistry(properties.stallFactor, properties.stallGrace)

    /**
     * `ROLE_INFRASTRUCTURE` wajib: tanpa aspectjweaver di classpath, pembuat proxy yang
     * aktif adalah `InfrastructureAdvisorAutoProxyCreator` yang HANYA melirik advisor
     * berperan infrastruktur — advisor biasa akan didaftarkan dengan patuh lalu diabaikan
     * diam-diam, dan seluruh pemantauan ini jadi hiasan.
     *
     * [Ordered.HIGHEST_PRECEDENCE] menaruhnya di lapis terluar, jadi durasi yang tercatat
     * termasuk commit transaksi dan kegagalan commit ikut terhitung sebagai job gagal.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun scheduledJobHealthAdvisor(registry: JobHealthRegistry): Advisor {
        val interceptor = MethodInterceptor { invocation ->
            registry.track(invocation.`this`, invocation.method) { invocation.proceed() }
        }
        return DefaultPointcutAdvisor(
            AnnotationMatchingPointcut(null, Scheduled::class.java, true),
            interceptor,
        ).apply { order = Ordered.HIGHEST_PRECEDENCE }
    }
}

/**
 * Mendaftarkan setiap job terjadwal beserta intervalnya begitu aplikasi siap.
 *
 * Intervalnya diambil dari pendaftaran Spring sendiri, bukan dibaca ulang dari anotasi:
 * `fixedDelayString` di repo ini semuanya berupa placeholder properti, jadi hanya Spring
 * yang tahu angka yang BENAR-BENAR berlaku setelah konfigurasi lingkungan diterapkan.
 *
 * Identitas job diambil dari `toString()` tugasnya (`paket.Kelas.metode`) karena Spring
 * membungkus runnable-nya dalam kelas internal yang tak membuka pembungkusnya — dan
 * `toString()` itulah satu-satunya jalan resmi yang tersisa menuju nama metode aslinya.
 */
@Component
class ScheduledJobDiscovery(
    private val registry: JobHealthRegistry,
    private val taskHolders: ObjectProvider<ScheduledTaskHolder>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Urutan eksplisit: [JobHealthMetrics] memasang pengukur atas daftar yang ini hasilkan. */
    @EventListener(ApplicationReadyEvent::class)
    @Order(JOB_DISCOVERY_ORDER)
    fun declareScheduledJobs() {
        var declared = 0
        taskHolders.forEach { holder ->
            holder.scheduledTasks.forEach { scheduled ->
                val task = scheduled.task
                val qualifiedMethod = task.runnable.toString()
                // Tugas yang bukan metode (mis. lambda terdaftar manual) tak punya nama
                // yang berarti; ia tetap terpantau lewat advisor, cuma tanpa interval.
                if (qualifiedMethod.count { it == '.' } >= 2) {
                    registry.declare(qualifiedMethod, (task as? IntervalTask)?.intervalDuration)
                    declared++
                }
            }
        }
        log.info("Pemantauan pekerjaan latar aktif untuk {} job terjadwal", declared)
    }

    companion object {
        const val JOB_DISCOVERY_ORDER = 0
    }
}
