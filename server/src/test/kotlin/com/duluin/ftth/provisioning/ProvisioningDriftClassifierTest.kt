package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.application.service.ProvisioningDriftClassifier
import com.duluin.ftth.provisioning.domain.model.DriftStatus
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProvisioningDriftClassifierTest {
    private val classifier = ProvisioningDriftClassifier()

    @Test
    fun `identical observations have no drift`() {
        val baseline = state(vlan = 110, external = false)

        val result = classifier.classify(baseline, baseline)

        assertThat(result).isEqualTo(DriftStatus.NONE)
    }

    @Test
    fun `external-only changes are benign`() {
        val baseline = state(vlan = 110, external = false)
        val observed = state(vlan = 110, external = true)

        val result = classifier.classify(baseline, observed)

        assertThat(result).isEqualTo(DriftStatus.BENIGN)
    }

    @Test
    fun `managed field changes conflict and legacy state is unknown`() {
        val baseline = state(vlan = 110, external = false)
        val changed = state(vlan = 111, external = false)

        assertThat(classifier.classify(baseline, changed)).isEqualTo(DriftStatus.CONFLICTING)
        assertThat(classifier.classify(baseline, legacy())).isEqualTo(DriftStatus.UNKNOWN)
    }

    private fun state(vlan: Int, external: Boolean) = NormalizedDeviceState.of(
        NormalizedField.VLAN_ID to NormalizedValue.number(vlan),
        NormalizedField.EXTERNAL to NormalizedValue.flag(external),
    )

    private fun legacy(): NormalizedDeviceState {
        val companion = NormalizedDeviceState::class.java.getDeclaredField("Companion").get(null)
        val method = companion.javaClass.declaredMethods.single { it.name.startsWith("rehydrateLegacy") }
        method.isAccessible = true
        return method.invoke(companion, "{\"legacy\":true}") as NormalizedDeviceState
    }
}
