package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseMutationMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CustomerDeploymentTwoCustomerIT : CustomerDeploymentFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["HTTP", "OWNER"])
    fun `two customers racing the same issued asset commit only the authorized customer`(surface: String) {
        val install = installation()
        val other = UUID.randomUUID()
        fixture(install.receipt.stock.token).transaction {
            sql("INSERT INTO customer(id,tenant_id,code,name,address) VALUES ('$other','$tenant','$other','Other customer','Test')")
        }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val attempts = listOf(install.customer, other).map { customer -> executor.submit<Int> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                val body = InstallCustomerAssetRequest(install.authorization, 0, null)
                when (surface) {
                    "HTTP" -> request("POST", "/api/customers/$customer/assets/install", install.receipt.receiver.first,
                        mapper.writeValueAsString(body), "customer-$customer").status
                    "OWNER" -> try {
                        authenticated(install) { context.getBean(CustomerAssetApi::class.java).install(customer, body, WarehouseMutationMetadata("customer-$customer")) }
                        201
                    } catch (failure: WarehouseContractException) { failure.error.code.httpStatus }
                    else -> error("Unknown surface")
                }
            } }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()

            assertThat(attempts.map { it.get(40, TimeUnit.SECONDS) }).containsExactly(201, 409)
        }
        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE customer_id='$other'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM customer_asset_installation WHERE customer_id='$other'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM onu WHERE customer_id='$other'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='${install.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE customer_id='${install.customer}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu WHERE customer_id='${install.customer}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization WHERE id='${install.authorization}' AND consumed")).isEqualTo("1")
        }
    }
}
