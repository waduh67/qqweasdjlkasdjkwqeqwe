package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningStepResult

interface ProvisioningAdapter {
    val vendor: String
    fun execute(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult
    fun capabilityReport(target: NasTarget): DeviceCapabilityReport
}

class ProvisioningAdapterRegistry(adapters: List<ProvisioningAdapter>) {
    private val byVendor = adapters.associateBy { it.vendor.uppercase() }

    fun forVendor(vendor: String): ProvisioningAdapter? = byVendor[vendor.uppercase()]
    val supportedVendors: Set<String> get() = byVendor.keys
}
