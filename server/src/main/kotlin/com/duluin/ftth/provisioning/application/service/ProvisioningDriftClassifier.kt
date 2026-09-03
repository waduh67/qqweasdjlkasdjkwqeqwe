package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.provisioning.domain.model.DriftStatus
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import org.springframework.stereotype.Component

@Component
class ProvisioningDriftClassifier {
    fun classify(baseline: NormalizedDeviceState, observed: NormalizedDeviceState): DriftStatus {
        if (baseline == observed) return DriftStatus.NONE
        if (baseline.legacyPayload != null || observed.legacyPayload != null) return DriftStatus.UNKNOWN
        return if (semanticFields(baseline) == semanticFields(observed)) DriftStatus.BENIGN else DriftStatus.CONFLICTING
    }

    fun semanticallyEquivalent(baseline: NormalizedDeviceState, observed: NormalizedDeviceState): Boolean =
        classify(baseline, observed) in setOf(DriftStatus.NONE, DriftStatus.BENIGN)

    private fun semanticFields(state: NormalizedDeviceState) = state.values - NormalizedField.EXTERNAL
}
