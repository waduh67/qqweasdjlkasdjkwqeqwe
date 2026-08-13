package com.duluin.ftth.customer.application.service

import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.network.CustomerEndpointProbe
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Memberi nama pada ujung kabel drop yang di peta cuma berupa id rumah.
 *
 * Satu query untuk berapa pun ujung yang ditanyakan: yang bertanya adalah panel
 * yang menelusuri seluruh kaki sebuah kotak sekaligus, dan menanya satu per satu
 * di situ berarti belasan query untuk satu kali membuka ODP.
 */
@Component
class CustomerEndpointNameProbe(
    private val customerRepository: CustomerRepository,
) : CustomerEndpointProbe {

    override fun namesOf(customerIds: Set<UUID>): Map<UUID, String> =
        if (customerIds.isEmpty()) {
            emptyMap()
        } else {
            customerRepository.findAllByIds(customerIds).associate { it.id to it.name }
        }
}
