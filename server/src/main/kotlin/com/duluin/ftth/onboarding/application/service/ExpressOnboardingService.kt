package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.onboarding.application.port.inbound.ExpressPsbCommand
import com.duluin.ftth.onboarding.application.port.inbound.ExpressPsbResult
import com.duluin.ftth.onboarding.application.port.inbound.ExpressOnboardingUseCase
import com.duluin.ftth.workorder.RaisePsbCommand
import com.duluin.ftth.workorder.WorkorderApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Orkestrasi PSB ekspres — module daun yang hanya memanggil kontrak publik customer, bng, dan
 * workorder (tak menyentuh internal module mana pun, jadi tak ada siklus module). Satu
 * `@Transactional` di sini + method Api hilir yang REQUIRED = satu transaksi fisik: pelanggan +
 * langganan + akun + WO terbentuk semua, atau tak satu pun (mis. planId salah → rollback total,
 * pelanggan pun tak tercipta). Langganan lahir PENDING → akun lahir PENDING dan BELUM ditulis ke
 * RADIUS; tak ada efek-samping jaringan AFTER_COMMIT yang menyala di tengah onboarding.
 */
@Service
class ExpressOnboardingService(
    private val customerApi: CustomerApi,
    private val bngApi: BngApi,
    private val workorderApi: WorkorderApi,
) : ExpressOnboardingUseCase {

    @Transactional
    override fun onboardPsb(command: ExpressPsbCommand): ExpressPsbResult {
        val customerId = customerApi.registerCustomer(
            RegisterCustomerCommand(
                code = command.code,
                name = command.name,
                phone = command.phone,
                email = command.email,
                address = command.address,
                location = command.location,
                areaId = command.areaId,
            ),
        )

        val subscriptionId = customerApi.openSubscription(customerId, command.planId, command.monthlyFeeOverride)

        // BRAS: pakai pilihan manual bila ada; kalau kosong, auto-pilih dari cakupan area pelanggan
        // (deterministik — tiap area dinaungi paling banyak satu BRAS). Tetap boleh null (akun lahir
        // tanpa BRAS, ditugaskan belakangan) bila area tak dipetakan ke BRAS mana pun.
        val nasId = command.nasId ?: command.areaId?.let { bngApi.resolveNasForArea(it) }

        val access = bngApi.provisionAccess(
            ProvisionAccessSpec(
                subscriptionId = subscriptionId,
                username = command.username,
                secret = command.secret,
                planId = command.planId,
                nasId = nasId,
                authType = command.serviceType,
                framedIp = command.framedIp,
            ),
        )

        val workOrder = workorderApi.raisePsb(
            RaisePsbCommand(
                customerId = customerId,
                subscriptionId = subscriptionId,
                title = command.title?.trim()?.takeIf { it.isNotEmpty() } ?: "PSB ${command.name}",
                description = command.description,
                areaId = command.areaId,
                scheduledAt = command.scheduledAt,
                assignees = command.assignees,
            ),
        )

        return ExpressPsbResult(
            customerId = customerId,
            subscriptionId = subscriptionId,
            accessId = access.accessId,
            username = access.username,
            workOrderId = workOrder.id,
            workOrderCode = workOrder.code,
        )
    }
}
