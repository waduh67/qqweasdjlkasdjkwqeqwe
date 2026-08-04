package com.duluin.ftth.platformbilling.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/** Penyedia pembayaran untuk menagih langganan tenant ke platform. */
enum class PlatformPaymentProvider { PAYWUZ, XENDIT, MIDTRANS }

/**
 * Setelan GLOBAL billing platform (satu baris). Menyimpan gateway aktif yang dipakai
 * menagih langganan tenant (default [PlatformPaymentProvider.PAYWUZ], bisa diganti
 * super-admin kapan saja) plus default grace/jatuh-tempo/tanggal-tagih yang berlaku
 * saat langganan tenant tak menimpanya sendiri.
 */
class PlatformSetting private constructor(
    val id: UUID,
    activeProvider: PlatformPaymentProvider,
    defaultGraceDays: Int,
    defaultDueDays: Int,
    defaultBillingDay: Int,
    currency: String,
) {
    var activeProvider: PlatformPaymentProvider = activeProvider
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
        activeProvider: PlatformPaymentProvider,
        defaultGraceDays: Int,
        defaultDueDays: Int,
        defaultBillingDay: Int,
        currency: String,
    ) {
        this.activeProvider = activeProvider
        this.defaultGraceDays = validateDays(defaultGraceDays, "Masa tenggang")
        this.defaultDueDays = validateDays(defaultDueDays, "Jatuh tempo")
        this.defaultBillingDay = validateBillingDay(defaultBillingDay)
        this.currency = validateCurrency(currency)
    }

    companion object {
        /** Setelan bawaan saat platform belum pernah dikonfigurasi. */
        fun default(): PlatformSetting = PlatformSetting(
            id = UuidV7.generate(),
            activeProvider = PlatformPaymentProvider.PAYWUZ,
            defaultGraceDays = 7,
            defaultDueDays = 7,
            defaultBillingDay = 1,
            currency = "IDR",
        )

        fun rehydrate(
            id: UUID,
            activeProvider: PlatformPaymentProvider,
            defaultGraceDays: Int,
            defaultDueDays: Int,
            defaultBillingDay: Int,
            currency: String,
        ): PlatformSetting = PlatformSetting(
            id, activeProvider, defaultGraceDays, defaultDueDays, defaultBillingDay, currency,
        )

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
