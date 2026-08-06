package com.duluin.ftth.billing

import com.duluin.ftth.billing.domain.model.BillingTaxSettings
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Menguji setelan pajak tenant murni domain: bawaan aman (fitur mati), penyaklaran PPN via
 * [BillingTaxSettings.effectivePpnRate], gabungan tarif kontribusi, serta validasi tarif —
 * tanpa Spring/DB.
 */
class BillingTaxSettingsTest {

    private fun default() = BillingTaxSettings.defaultFor(UuidV7.generate())

    @Test
    fun `bawaan mematikan kedua fitur tapi mengisi tarif lazim`() {
        val s = default()

        assertThat(s.ppnEnabled).isFalse()
        assertThat(s.regulatoryEnabled).isFalse()
        assertThat(s.ppnRate).isEqualByComparingTo("0.1100")
        assertThat(s.bhpRate).isEqualByComparingTo("0.0050")
        assertThat(s.usoRate).isEqualByComparingTo("0.0125")
    }

    @Test
    fun `PPN mati menghasilkan tarif efektif null`() {
        assertThat(default().effectivePpnRate()).isNull()
    }

    @Test
    fun `PPN nyala menghasilkan tarif efektif`() {
        val s = default()
        s.update(
            ppnEnabled = true,
            ppnRate = BigDecimal("0.11"),
            regulatoryEnabled = false,
            bhpRate = BigDecimal("0.005"),
            usoRate = BigDecimal("0.0125"),
        )

        assertThat(s.effectivePpnRate()).isEqualByComparingTo("0.11")
    }

    @Test
    fun `tarif kontribusi nol saat pelaporan mati`() {
        assertThat(default().regulatoryRate()).isEqualByComparingTo("0")
    }

    @Test
    fun `tarif kontribusi menjumlahkan BHP dan USO saat pelaporan nyala`() {
        val s = default()
        s.update(
            ppnEnabled = false,
            ppnRate = BigDecimal("0.11"),
            regulatoryEnabled = true,
            bhpRate = BigDecimal("0.005"),
            usoRate = BigDecimal("0.0125"),
        )

        assertThat(s.regulatoryRate()).isEqualByComparingTo("0.0175") // 0.005 + 0.0125
    }

    @Test
    fun `update menormalkan tarif ke skala 4`() {
        val s = default()
        s.update(
            ppnEnabled = true,
            ppnRate = BigDecimal("0.11"),
            regulatoryEnabled = true,
            bhpRate = BigDecimal("0.005"),
            usoRate = BigDecimal("0.0125"),
        )

        assertThat(s.ppnRate.scale()).isEqualTo(4)
        assertThat(s.bhpRate.scale()).isEqualTo(4)
    }

    @Test
    fun `tarif negatif ditolak`() {
        assertThatThrownBy {
            default().update(
                ppnEnabled = true,
                ppnRate = BigDecimal("-0.01"),
                regulatoryEnabled = false,
                bhpRate = BigDecimal("0.005"),
                usoRate = BigDecimal("0.0125"),
            )
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `tarif satu atau lebih ditolak`() {
        assertThatThrownBy {
            default().update(
                ppnEnabled = true,
                ppnRate = BigDecimal.ONE,
                regulatoryEnabled = false,
                bhpRate = BigDecimal("0.005"),
                usoRate = BigDecimal("0.0125"),
            )
        }.isInstanceOf(ValidationException::class.java)
    }
}
