package com.duluin.ftth

import com.duluin.ftth.common.infrastructure.observability.JobHealth
import com.duluin.ftth.common.infrastructure.observability.JobHealthRegistry
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

/**
 * Uji pemantauan aplikasi terhadap dirinya sendiri.
 *
 * Yang dijaga:
 * 1. SETIAP metode `@Scheduled` terdaftar beserta interval yang berlaku — inilah yang
 *    membuat job baru ikut terpantau tanpa penulisnya perlu melakukan apa pun.
 * 2. Advisor-nya sungguh terpasang: denyut nadi bertambah ketika job betulan berjalan.
 *    Tanpa uji ini seluruh mekanisme bisa saja terdaftar rapi lalu diabaikan diam-diam
 *    oleh pembuat proxy, dan tak ada yang tahu sampai ada job macet yang lolos.
 * 3. `/actuator/prometheus` tertutup tanpa token dan terbuka dengan token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObservabilityIT {

    /** Sama persis dengan `ftth.observability.metrics-token` di application-test.yml. */
    private val metricsToken = "token-metrik-uji-yang-cukup-panjang-untuk-serius"

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jobs: JobHealthRegistry

    @Test
    fun `seluruh job terjadwal terdaftar beserta intervalnya`() {
        val declared = jobs.snapshot().associateBy { it.name }

        assertThat(declared.keys).contains(
            "OltPollingScheduler.pollAll",
            "BillingScheduler.issueInvoices",
            "RadiusAccountingPoller.poll",
            "PlatformBillingScheduler.enforceOverdue",
            "CpeSyncScheduler.syncAll",
        )
        // Interval diambil dari pendaftaran Spring, jadi angkanya yang BENAR-BENAR berlaku
        // setelah properti lingkungan diterapkan — bukan hasil membaca ulang anotasi.
        assertThat(declared.getValue("BillingScheduler.issueInvoices").interval).isEqualTo(Duration.ofHours(12))
        assertThat(declared.getValue("RadiusAccountingPoller.poll").interval).isEqualTo(Duration.ofSeconds(30))
        assertThat(declared.values.map { it.module }).contains("monitoring", "billing", "bng", "cpe")
        assertThat(declared.values).allSatisfy { assertThat(it.stalled).isFalse() }
    }

    @Test
    fun `denyut nadi tercatat ketika job benar-benar berjalan`() {
        val job = awaitJobRun("RadiusProvisioningDispatcher.dispatch")

        assertThat(job.runs).isPositive()
        assertThat(job.lastSuccessAt).isNotNull()
        assertThat(job.failures).isZero()
        assertThat(job.stalled).isFalse()
    }

    @Test
    fun `endpoint metrik tertutup tanpa token dan terbuka dengan token`() {
        assertThat(mockMvc.perform(get("/actuator/prometheus")).andReturn().response.status)
            .isIn(401, 403)
        assertThat(
            mockMvc.perform(get("/actuator/prometheus").header("X-Metrics-Token", "token-yang-salah"))
                .andReturn().response.status,
        ).isIn(401, 403)

        awaitJobRun("RadiusProvisioningDispatcher.dispatch")
        val scraped = mockMvc.perform(get("/actuator/prometheus").header("X-Metrics-Token", metricsToken))
            .andReturn().response

        assertThat(scraped.status).isEqualTo(200)
        assertThat(scraped.contentAsString)
            .contains("ftth_job_success_age_seconds")
            .contains("ftth_job_runs_total")
            .contains("ftth_job_stalled")
            // `job_name`, bukan `job`: label `job` sudah dipakai Prometheus untuk nama
            // scrape-config-nya sendiri, dan yang bentrok diganti diam-diam jadi
            // `exported_job` — aturan alert yang menyebut `job=` tak akan pernah menyala.
            .contains("""job_name="RadiusProvisioningDispatcher.dispatch"""")
            .doesNotContain("""ftth_job_stalled{job="""")
    }

    @Test
    fun `kolam utas penjadwal lebih dari satu dan ikut terukur`() {
        val scraped = mockMvc.perform(get("/actuator/prometheus").header("X-Metrics-Token", metricsToken))
            .andReturn().response.contentAsString

        // Satu utas (bawaan Spring) berarti satu job yang menggantung membekukan semuanya;
        // metrik kolam inilah yang memperlihatkannya sebelum jadi keluhan pelanggan.
        val coreThreads = scraped.lineSequence()
            .first { it.startsWith("""executor_pool_core_threads{name="taskScheduler"}""") }
            .substringAfterLast(' ').toDouble()

        assertThat(coreThreads).isGreaterThan(1.0)
        assertThat(scraped).contains("""executor_queued_tasks{name="taskScheduler"}""")
    }

    @Test
    fun `daftar kesehatan job hanya untuk platform admin`() {
        assertThat(mockMvc.perform(get("/api/platform/jobs")).andReturn().response.status).isEqualTo(401)

        val token = loginAsPlatformRoot()
        val json = mockMvc.perform(get("/api/platform/jobs").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(json).contains("BillingScheduler.issueInvoices").contains(""""module":"billing"""")
        // Durasi dikirim sebagai angka detik, bukan "PT12H": halaman tak perlu mengurai ISO-8601.
        assertThat(JsonPath.read<List<Int>>(json, "$[?(@.name=='BillingScheduler.issueInvoices')].intervalSeconds"))
            .containsExactly(43_200)
        assertThat(JsonPath.read<List<Boolean>>(json, "$[*].stalled")).doesNotContain(true)
    }

    private fun loginAsPlatformRoot(): String {
        val body = """{"tenantSlug":"platform","email":"root@ftth.local","password":"rootadmin123"}"""
        val json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    /**
     * Dispatcher RADIUS berjadwal PT10S dan ronde pertamanya jalan begitu aplikasi hidup,
     * jadi denyut yang tak kunjung muncul berarti advisor pemantaunya tidak terpasang.
     */
    private fun awaitJobRun(name: String): JobHealth {
        repeat(100) { attempt ->
            val job = jobs.health(name)
            if (job != null && job.runs > 0) return job
            if (attempt < 99) Thread.sleep(100)
        }
        error("job '$name' tak pernah berdenyut dalam 10 detik — advisor pemantau tidak terpasang")
    }
}
