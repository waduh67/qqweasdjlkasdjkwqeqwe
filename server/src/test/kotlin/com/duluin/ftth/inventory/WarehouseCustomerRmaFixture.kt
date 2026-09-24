package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode

abstract class WarehouseCustomerRmaFixture : WarehouseRepairFixture() {
    protected data class RmaCase(val repair: RepairSetup, val work: String, val workRevision: Long, val inspected: JsonNode) {
        val receipt get() = repair.returned.old.installation.receipt
        val customer get() = repair.returned.old.installation.customer
        val body get() = """{"expectedRevision":${inspected.path("revision").asLong()},"workOrderId":"$work","workOrderRevision":$workRevision,"technicianId":"${receipt.receiver.second}","transitLocationId":"${receipt.transit}","technicianLocationId":"${receipt.field}","observedSerial":"${repair.serial}","evidenceReference":"signed-rma-handover"}"""
        val ack get() = """{"expectedRevision":1,"observedSerial":"${repair.serial}","evidenceReference":"technician-received-rma"}"""
        val authorization get() = """{"expectedRevision":$workRevision,"assetId":"${repair.asset}","issueLineId":null,"purpose":"RETURN_CUSTOMER_RMA","ownershipMode":"SALE","previousAssignmentId":"${repair.returned.old.installation.operation}","repairCaseId":"${inspected.path("repair").path("id").asString()}"}"""
    }
    protected fun prepareRma(): RmaCase {
        val setup = repairSetup()
        val inspected = inspectRepair(setup, receiveRepair(setup, dispatchRepair(setup)))
        val receipt = setup.returned.old.installation.receipt
        val work = workOrder(setup.token, "REPAIR", setup.returned.old.installation.customer.toString())
        assign(setup.token, work, receipt.receiver.second)
        val revision = summary(setup.token, work).path("revisions").path("workOrderRevision").asLong()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${setup.returned.quarantine}", setup.token,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        return RmaCase(setup, work, revision, inspected)
    }
    protected fun dispatchRma(case: RmaCase): JsonNode {
        val response = request("POST", "${case.repair.path}/rma-handover", case.repair.token, case.body, "rma-outbound")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString)
    }
    protected fun receiveRma(case: RmaCase, outbound: JsonNode): String {
        val response = request("POST", "/api/v1/warehouse/rma-handovers/${outbound.path("id").asString()}/acknowledge",
            case.receipt.receiver.first, case.ack, "rma-ack")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return response.contentAsString
    }
    protected data class RmaInstallation(val source: RmaCase, val authorization: String, val operation: String)
    protected fun installRma(case: RmaCase = prepareRma()): RmaInstallation {
        val outbound = dispatchRma(case)
        receiveRma(case, outbound)
        val permit = request("POST", "/api/work-orders/${case.work}/assets/authorize", case.receipt.receiver.first,
            case.authorization, "rma-authorization")
        assertThat(permit.status).withFailMessage(permit.contentAsString).isEqualTo(200)
        val body = mapper.readTree(permit.contentAsString)
        val authorization = body.path("authorizationId").asString()
        val installed = request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first,
            """{"authorizationId":"$authorization","expectedRevision":0,"topology":null}""", "rma-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        return RmaInstallation(case, authorization, body.path("operationId").asString())
    }

}
