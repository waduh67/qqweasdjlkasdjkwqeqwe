package com.karuhun.ftth.collector.adapter

import com.karuhun.ftth.contract.DeviceCapabilityReport
import com.karuhun.ftth.contract.NasTarget
import com.karuhun.ftth.contract.OltTarget
import com.karuhun.ftth.contract.ProvisioningPlanStepCommand
import com.karuhun.ftth.contract.ProvisioningStepResult

interface ProvisioningAdapter {
    val vendor: String
    fun execute(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult
    fun capabilityReport(target: NasTarget): DeviceCapabilityReport
}

interface OltProvisioningAdapter {
    val vendor: String
    fun execute(target: OltTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult
    fun capabilityReport(target: OltTarget): DeviceCapabilityReport
}

class ProvisioningAdapterRegistry(adapters: List<ProvisioningAdapter>) {
    private val byVendor = adapters.associateBy { it.vendor.uppercase() }

    fun forVendor(vendor: String): ProvisioningAdapter? = byVendor[vendor.uppercase()]
    val supportedVendors: Set<String> get() = byVendor.keys
}

class OltProvisioningAdapterRegistry(adapters: List<OltProvisioningAdapter>) {
    private val byVendor = adapters.associateBy { it.vendor.uppercase() }

    fun forVendor(vendor: String): OltProvisioningAdapter? = byVendor[vendor.uppercase()]
    val supportedVendors: Set<String> get() = byVendor.keys
}
