package com.duluin.ftth.platformbilling.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Setelan GLOBAL billing platform (satu baris). Menyimpan default grace/jatuh-tempo/tanggal-tagih
 * dan harga bulanan bawaan yang berlaku saat langganan tenant tak menimpanya sendiri.
 *
 * Penyedia pembayaran TIDAK lagi di sini — seluruh penagihan langganan berjalan di akun master
 * Pivot ([com.duluin.ftth.billing.domain.model.PivotMasterConfig], dikelola di setelan platform),
 * jadi tak ada lagi "gateway aktif" yang bisa dipilih.
 */
class PlatformSetting private constructor(
    val id: UUID,
    defaultGraceDays: Int,
    defaultDueDays: Int,
    defaultBillingDay: Int,
    defaultMonthlyFee: BigDecimal,
    currency: String,
) {
    /** Harga bulanan bawaan untuk tenant baru saat super-admin tak mengisi harga khusus. */
    var defaultMonthlyFee: BigDecimal = defaultMonthlyFee
        private set

    var defaultGraceDays: Int = defaultGraceDays
        private set

    var defaultDueDays: Int = defaultDueDays
        private set

    var defaultBillingDay: Int = defaultBillingDay
        private set

    var currency: String = currency
        private set

    fun update(
        defaultGraceDays: Int,
        defaultDueDays: Int,
        defaultBillingDay: Int,
        defaultMonthlyFee: BigDecimal,
        currency: String,
    ) {
        this.defaultGraceDays = validateDays(defaultGraceDays, "Masa tenggang")
        this.defaultDueDays = validateDays(defaultDueDays, "Jatuh tempo")
        this.defaultBillingDay = validateBillingDay(defaultBillingDay)
        this.defaultMonthlyFee = validateFee(defaultMonthlyFee)
        this.currency = validateCurrency(currency)
    }

    companion object {
        /** Setelan bawaan saat platform belum pernah dikonfigurasi. */
        fun default(): PlatformSetting = PlatformSetting(
            id = UuidV7.generate(),
            defaultGraceDays = 7,
            defaultDueDays = 7,
            defaultBillingDay = 1,
            defaultMonthlyFee = BigDecimal.ZERO.setScale(2),
            currency = "IDR",
        )

        fun rehydrate(
            id: UUID,
            defaultGraceDays: Int,
            defaultDueDays: Int,
            defaultBillingDay: Int,
            defaultMonthlyFee: BigDecimal,
            currency: String,
        ): PlatformSetting = PlatformSetting(
            id, defaultGraceDays, defaultDueDays, defaultBillingDay, defaultMonthlyFee, currency,
        )

        private fun validateFee(fee: BigDecimal): BigDecimal {
            if (fee.signum() < 0) throw ValidationException("Harga bulanan default tidak boleh negatif")
            return fee.setScale(2, RoundingMode.HALF_UP)
        }

        private fun validateDays(days: Int, label: String): Int {
            if (days < 0) throw ValidationException("$label tidak boleh negatif")
            return days
        }

        private fun validateBillingDay(day: Int): Int {
            if (day !in 1..28) throw ValidationException("Tanggal tagih harus 1-28")
            return day
        }

        private fun validateCurrency(currency: String): String {
            val trimmed = currency.trim().uppercase()
            if (trimmed.length != 3) throw ValidationException("Mata uang harus 3 huruf (mis. IDR)")
            return trimmed
        }
    }
}
