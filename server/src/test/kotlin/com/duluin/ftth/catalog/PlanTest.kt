package com.duluin.ftth.catalog

import com.duluin.ftth.catalog.domain.model.Plan
import com.duluin.ftth.catalog.domain.model.PlanAttributes
import com.duluin.ftth.catalog.domain.model.ServiceType
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Menguji validasi & generator rate-limit paket — murni domain. */
class PlanTest {

    private fun attrs(
        downMbps: Int = 50,
        upMbps: Int = 10,
        downBurstMbps: Int? = null,
        upBurstMbps: Int? = null,
        downThresholdMbps: Int? = null,
        upThresholdMbps: Int? = null,
        burstTimeSec: Int? = null,
        downMinMbps: Int? = null,
        upMinMbps: Int? = null,
        priority: Int = Plan.DEFAULT_PRIORITY,
        fupEnabled: Boolean = false,
        fupQuotaMb: Long? = null,
        fupDownMbps: Int? = null,
        fupUpMbps: Int? = null,
        price: BigDecimal = BigDecimal("150000"),
        serviceTypes: Set<ServiceType> = setOf(ServiceType.PPPOE),
    ) = PlanAttributes(
        name = "Home 50",
        description = "Paket rumahan",
        price = price,
        downMbps = downMbps,
        upMbps = upMbps,
        downBurstMbps = downBurstMbps,
        upBurstMbps = upBurstMbps,
        downThresholdMbps = downThresholdMbps,
        upThresholdMbps = upThresholdMbps,
        burstTimeSec = burstTimeSec,
        downMinMbps = downMinMbps,
        upMinMbps = upMinMbps,
        priority = priority,
        fupEnabled = fupEnabled,
        fupQuotaMb = fupQuotaMb,
        fupDownMbps = fupDownMbps,
        fupUpMbps = fupUpMbps,
        serviceTypes = serviceTypes,
    )

    private fun plan(a: PlanAttributes) = Plan.create(UuidV7.generate(), a)

    // ---- Generator Mikrotik-Rate-Limit (urutan up/down = rx/tx) ----

    @Test
    fun `rate saja menghasilkan rx per tx polos`() {
        assertThat(plan(attrs(downMbps = 50, upMbps = 10)).rateLimitString()).isEqualTo("10M/50M")
    }

    @Test
    fun `burst menambah satu grup`() {
        val s = plan(attrs(downBurstMbps = 100, upBurstMbps = 20)).rateLimitString()
        assertThat(s).isEqualTo("10M/50M 20M/100M")
    }

    @Test
    fun `burst plus threshold plus waktu terangkai lengkap`() {
        val s = plan(
            attrs(
                downBurstMbps = 100, upBurstMbps = 20,
                downThresholdMbps = 75, upThresholdMbps = 15,
                burstTimeSec = 8,
            ),
        ).rateLimitString()
        assertThat(s).isEqualTo("10M/50M 20M/100M 15M/75M 8/8")
    }

    @Test
    fun `prioritas non-default tanpa burst menjejali placeholder`() {
        val s = plan(attrs(priority = 1)).rateLimitString()
        assertThat(s).isEqualTo("10M/50M 0M/0M 0M/0M 0/0 1")
    }

    @Test
    fun `limit-at memaksa seluruh grup sebelumnya jadi placeholder`() {
        val s = plan(attrs(downMinMbps = 25, upMinMbps = 5)).rateLimitString()
        assertThat(s).isEqualTo("10M/50M 0M/0M 0M/0M 0/0 8 5M/25M")
    }

    @Test
    fun `FUP menghasilkan rate throttle terpisah, null saat nonaktif`() {
        assertThat(plan(attrs()).fupRateLimitString()).isNull()
        val fup = plan(attrs(fupEnabled = true, fupQuotaMb = 300_000, fupDownMbps = 5, fupUpMbps = 2))
        assertThat(fup.fupRateLimitString()).isEqualTo("2M/5M")
    }

    // ---- Validasi ----

    @Test
    fun `nama terlalu pendek ditolak`() {
        assertThatThrownBy { plan(attrs().copy(name = "A")) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `harga negatif ditolak`() {
        assertThatThrownBy { plan(attrs(price = BigDecimal("-1"))) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `burst lebih kecil dari rate ditolak`() {
        assertThatThrownBy { plan(attrs(downBurstMbps = 40, upBurstMbps = 20)) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `threshold tanpa burst ditolak`() {
        assertThatThrownBy { plan(attrs(downThresholdMbps = 60, upThresholdMbps = 12)) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `burst hanya satu arah ditolak`() {
        assertThatThrownBy { plan(attrs(downBurstMbps = 100)) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `FUP aktif tanpa kuota atau kecepatan ditolak`() {
        assertThatThrownBy { plan(attrs(fupEnabled = true)) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `prioritas di luar 1-8 ditolak`() {
        assertThatThrownBy { plan(attrs(priority = 9)) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `tipe layanan kosong ditolak`() {
        assertThatThrownBy { plan(attrs(serviceTypes = emptySet())) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `harga dinormalkan ke dua desimal`() {
        assertThat(plan(attrs(price = BigDecimal("150000.005"))).attributes.price).isEqualByComparingTo("150000.01")
    }
}
