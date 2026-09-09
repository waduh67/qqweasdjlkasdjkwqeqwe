package com.duluin.ftth.inventory

import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.config.RuntimeBeanReference
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.support.GenericApplicationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

class WarehouseContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build()
    private val root = "com.duluin.ftth."
    private val id = "00000000-0000-0000-0000-000000000001"
    private val apis = listOf(
        "inventory.InventoryMaterialApi", "inventory.InventoryWorkOrderValidationPort",
        "inventory.InventoryDeploymentApi", "inventory.InventoryTenantCutoverApi",
        "inventory.InventoryWarehouseScopeApi", "inventory.MaterialConsumptionApiV2",
        "inventory.InventoryReservationApi", "inventory.InventoryMaterialReservationApi",
        "workorder.WorkOrderMaterialContextApi", "customer.CustomerAssetApi", "iam.CurrentAuthorityApi",
        "common.security.AuthorityFence", "common.security.AuthorityChangeFence",
    )
    private val fixtures = mapOf(
        "inventory.ReplaceMaterialPlanRequest" to
            """{"expectedRevision":2,"materialMode":"NONE","reason":"Inspection only","lines":[]}""",
        "inventory.MaterialDocumentRequest" to """{"documentId":"$id","expectedRevision":2}""",
        "inventory.ReportMaterialUseRequest" to
            """{"expectedRevision":2,"planRevision":3,"lines":[{"issueLineId":"$id","stockIdentityId":"$id","quantityBase":"82500","baseUnit":"MM"}]}""",
        "inventory.SettlementCheckRequest" to """{"expectedRevision":2,"planRevision":3,"useRevision":4}""",
        "inventory.DeploymentIntentRequest" to
            """{"expectedRevision":2,"assetId":"$id","issueLineId":"$id","purpose":"INSTALL"}""",
        "inventory.ConsumeDeploymentRequest" to """{"authorizationId":"$id","expectedRevision":2}""",
        "inventory.AcceptAssetHandoverRequest" to
            """{"assignmentId":"$id","expectedRevision":2,"evidenceId":"$id"}""",
        "inventory.ApprovalSourceRequest" to """{"documentId":"$id","expectedRevision":2}""",
        "customer.InstallCustomerAssetRequest" to
            """{"authorizationId":"$id","expectedRevision":2,"topology":null}""",
    )

    @Test
    fun `required public contracts exist without permissive default methods`() {
        apis.forEach { name ->
            val api = type(name)
            assertThat(api.isInterface).describedAs(name).isTrue()
            assertThat(api.methods.toList()).isNotEmpty().allSatisfy {
                assertThat(Modifier.isAbstract(it.modifiers)).describedAs(it.toString()).isTrue()
            }
        }
    }

    @Test
    fun `missing required binding fails rather than fabricating empty success`() {
        apis.forEach { name ->
            val api = type(name)
            GenericApplicationContext().use { context ->
                val consumer = RootBeanDefinition(if (api == InventoryWorkOrderValidationPort::class.java)
                    RequiredDeploymentConsumer::class.java else RequiredContractConsumer::class.java)
                consumer.constructorArgumentValues.addIndexedArgumentValue(0, RuntimeBeanReference(api))
                context.registerBeanDefinition("requiredContractConsumer", consumer)
                assertThatThrownBy { context.refresh() }.hasRootCauseInstanceOf(NoSuchBeanDefinitionException::class.java)
            }
        }
    }

    @Test
    fun `deployment validation inversion has exact inventory owned signature`() {
        val method = type("inventory.InventoryWorkOrderValidationPort")
            .getMethod("lockAndValidate", type("inventory.DeploymentValidationContext"))
        assertThat(method.returnType).isEqualTo(type("inventory.ValidatedWorkOrderContext"))
        assertFields("inventory.DeploymentBinding", setOf(
            "authorizationId", "operationId", "tenantId", "actorId", "assetId", "stockIdentityId",
            "issueLineId", "workOrderId", "customerId", "purpose", "ownershipMode", "revisions",
            "issueRevision", "assetRevision", "authorityEpoch", "cutoverEpoch", "repairReturn",
            "previousAssignmentId", "previousAssignmentRevision",
        ))
        assertFields("inventory.ValidatedWorkOrderContext", setOf(
            "authorizationId", "workOrderId", "customerId", "actorId", "purpose", "revisions", "authorityEpoch", "cutoverEpoch",
        ))
    }

    @Test
    fun `wire request fixtures round trip and default device intent is loan`() {
        fixtures.forEach { (name, json) ->
            val value = mapper.readValue(json, type(name))
            val actual = mapper.readTree(mapper.writeValueAsString(value))
            mapper.readTree(json).properties().forEach { (key, expected) ->
                assertThat(actual.get(key)).describedAs("$name.$key").isEqualTo(expected)
            }
        }
        val intent = mapper.readValue(fixtures.getValue("inventory.DeploymentIntentRequest"), type("inventory.DeploymentIntentRequest"))
        assertThat(mapper.readTree(mapper.writeValueAsString(intent)).get("ownershipMode").asString()).isEqualTo("LOAN")
    }

    @Test
    fun `wire requests reject every client supplied authority field`() {
        val forbidden = setOf("tenantId", "actorId", "approverId", "approvers", "requesterId", "custodianId",
            "tier", "tiers", "hash", "payloadHash", "movementId", "movementTarget", "authorityEpoch",
            "cutoverEpoch", "permissions", "warehouseScope", "legacy", "source", "legalOwner", "acceptedAt")
        fixtures.forEach { (name, json) ->
            val requestType = type(name)
            assertThat(requestType.declaredFields.map { it.name }.toSet().intersect(forbidden)).isEmpty()
            forbidden.forEach { field ->
                val injected = json.dropLast(1) + ",\"$field\":\"spoofed\"}"
                assertThatThrownBy { mapper.readValue(injected, requestType) }.describedAs("$name rejects $field")
                    .isInstanceOf(tools.jackson.core.JacksonException::class.java)
            }
        }
    }

    @Test
    fun `malformed wire unknown enums and numeric enums fail closed`() {
        val requestType = type("inventory.DeploymentIntentRequest")
        val valid = fixtures.getValue("inventory.DeploymentIntentRequest")
        listOf("{", valid.replace("INSTALL", "BYPASS"), valid.replace("\"INSTALL\"", "0"),
            valid.replace(id, "not-a-uuid"), valid.replace("\"assetId\":\"$id\",", "")).forEach {
            assertThatThrownBy { mapper.readValue(it, requestType) }.isInstanceOf(tools.jackson.core.JacksonException::class.java)
        }
    }

    @Test
    fun `state and ownership values are stable wire strings`() {
        val states = mapOf(
            "MaterialMode" to "NONE MATERIAL_REQUIRED", "WarehouseTracking" to "SERIAL LOT BULK",
            "WarehouseBaseUnit" to "EA MM", "WarehouseCondition" to "SERVICEABLE QUARANTINE DAMAGED SCRAP",
            "AssetLegalOwner" to "ISP CUSTOMER UNKNOWN", "AssetOwnershipMode" to "LOAN SALE",
            "AssetHandoverState" to "PENDING ACCEPTED", "WarehouseMasterState" to "ACTIVE ARCHIVED",
            "WarehouseReceiptState" to "DRAFT RECEIVED_IN_INSPECTION PUTAWAY CLOSED",
            "WarehouseTransferState" to "DRAFT DISPATCHED PART_RECEIVED RECEIVED DISCREPANCY",
            "MaterialDemandState" to "DRAFT SUBMITTED PART_RESERVED RESERVED PART_ISSUED ISSUED SETTLING CLOSED CANCELLED",
            "WarehouseIssueState" to "DRAFT PICKED DISPATCHED PART_RECEIVED RECEIVED",
            "WarehouseReturnState" to "DRAFT DISPATCHED RECEIVED_IN_INSPECTION ACCEPTED REPAIR SUPPLIER_RETURN SCRAP",
            "WarehouseCountState" to "DRAFT COUNTING SUBMITTED APPROVED POSTED RECOUNT_REQUIRED",
            "MaterialSettlementState" to "OPEN RESIDUAL_PENDING CLOSED",
            "MaterialQaState" to "PENDING APPROVED REJECTED", "MaterialEffect" to "SETTLEMENT_VERIFY",
            "WarehouseCutoverState" to "LEGACY VALIDATING ENFORCED",
            "WarehouseAdmission" to "LEGACY_UNRESOLVED VERIFIED",
            "WarehouseIdentityClaimState" to "LEGACY_RESERVED CONFLICT ADMITTED RETIRED",
            "AssetProvenance" to "RECEIPT OPENING_BALANCE UNKNOWN",
            "DeploymentPurpose" to "INSTALL REPLACE REMOVE RETURN_CUSTOMER_RMA",
        )
        states.forEach { (name, expected) ->
            val enumType = type("inventory.$name")
            assertThat(enumType.enumConstants.map { it.toString() }).containsExactlyElementsOf(expected.split(" "))
            enumType.enumConstants.forEach {
                assertThat(mapper.writeValueAsString(it)).isEqualTo("\"$it\"")
                assertThat(mapper.readValue("\"$it\"", enumType)).isEqualTo(it)
            }
        }
    }

    @Test
    fun `stable errors distinguish malformed forbidden missing and conflict`() {
        val codes = type("inventory.WarehouseErrorCode")
        val expected = mapOf(400 to "MALFORMED_REQUEST", 401 to "UNAUTHENTICATED", 403 to "FORBIDDEN",
            404 to "NOT_FOUND", 409 to "INSUFFICIENT_STOCK STALE_REVISION SOURCE_NOT_VERIFIED WRONG_CUSTODIAN ACTIVE_ASSIGNMENT_EXISTS APPROVAL_REQUIRED COUNT_STALE IDEMPOTENCY_CONFLICT STALE_AUTHORITY CUTOVER_REQUIRED STALE_CUTOVER USE_WORKORDER_ASSET_WORKFLOW INDEPENDENT_APPROVER_REQUIRED COST_BASIS_REQUIRED CURRENCY_MISMATCH")
        assertThat(codes.enumConstants.map { it.toString() }).containsExactlyInAnyOrderElementsOf(expected.values.flatMap { it.split(" ") })
        expected.forEach { (status, names) -> names.split(" ").forEach { name ->
            val code = mapper.readValue("\"$name\"", codes)
            assertThat(codes.getMethod("getHttpStatus").invoke(code)).isEqualTo(status)
        } }
    }

    @Test
    fun `C7 permissions retain old families and add exact new families`() {
        val pairs = setOf("sku", "receipt", "request", "issue", "transfer", "return", "count", "provenance")
        val required = pairs.flatMap { resource -> listOf("inventory.$resource.view", "inventory.$resource.manage") } +
            listOf("inventory.report.view", "inventory.cost.view", "inventory.location.view", "inventory.location.manage",
                "inventory.item.view", "inventory.item.manage", "inventory.custody.view", "inventory.custody.manage",
                "inventory.approval.view", "inventory.approval.request", "inventory.approval.decide", "inventory.approval.emergency", "inventory.approval.manage",
                "inventory.request.override")
        val actual = PermissionCatalog.ALL.map { it.code.value }.filter { it.startsWith("inventory.") }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(required).doesNotHaveDuplicates()
        assertThat(actual).allMatch { Regex("[a-z]+\\.[a-z]+\\.[a-z]+").matches(it) }
    }

    @Test
    fun `material revisions quantity units cost lineage and original response are explicit`() {
        assertFields("inventory.MaterialRevisions", setOf("workOrderRevision", "planRevision", "useRevision", "settlementRevision"))
        assertFields("inventory.WarehouseQuantity", setOf("quantityBase", "baseUnit", "displayQuantity", "displayUnit"))
        assertFields("inventory.ReceiptCostSnapshot", setOf("totalMinor", "currency", "costBasisQuantityBase"))
        assertFields("inventory.WarehouseMutationMetadata", setOf("idempotencyKey"))
        assertFields("inventory.WarehouseOperationReceipt", setOf("operationId", "documentId", "documentRevision", "originalStatus", "originalBody", "recordedAt"))
        assertThat(type("inventory.WarehouseQuantity").getDeclaredField("quantityBase").type).isEqualTo(String::class.java)
        assertThat(type("inventory.ReceiptCostSnapshot").getDeclaredField("totalMinor").type).isEqualTo(String::class.java)
        assertThat(type("inventory.CustomerMaterialFactV2").getDeclaredField("quantity").type).isEqualTo(type("inventory.WarehouseQuantity"))
        assertThat(CustomerMaterialFactRef::class.java.getDeclaredField("quantity").type).isEqualTo(Int::class.javaPrimitiveType)
    }

    @Test
    fun `identity is not a transaction fence and locks are explicit`() {
        val identity = type("common.security.SessionIdentity")
        assertThat(type("common.security.AuthorityFence").isAssignableFrom(identity)).isFalse()
        assertThat(type("iam.CurrentAuthorityApi").getMethod("lockCurrent").returnType).isEqualTo(type("iam.CurrentAuthority"))
        assertThat(type("iam.CurrentAuthorityApi").getMethod("lockForChange").returnType).isEqualTo(type("common.security.AuthorityChangeFence"))
        assertThat(type("workorder.WorkOrderMaterialContextApi").methods.map { it.name }).contains("read", "lock")
        assertThat(type("inventory.InventoryTenantCutoverApi").methods.map { it.name }).contains("lockForCommand", "lockForTransition")
    }

    @Test
    fun `new contract closure never imports internal implementations or inverse owners`() {
        val inspected = mutableSetOf<Class<*>>()
        fun inspect(contract: Class<*>) {
            if (!contract.name.startsWith(root) || !inspected.add(contract)) return
            assertThat(contract.name).doesNotContain(".adapter.", ".application.", ".domain.", ".fulfillment.", ".monitoring.")
            contract.declaredFields.forEach { inspect(it.type) }
            contract.declaredMethods.forEach { method ->
                inspect(method.returnType)
                method.parameterTypes.forEach { inspect(it) }
                assertThat(method.toGenericString()).doesNotContain(".adapter.", ".application.", ".domain.", "jakarta.persistence")
            }
        }
        apis.forEach { inspect(type(it)) }
        assertThat(inspected.size).isGreaterThan(25)
        apis.forEach { name ->
            val source = Path.of("src/main/kotlin", root.replace('.', '/') + name.replace('.', '/') + ".kt")
            if (Files.exists(source)) {
                assertThat(Files.readString(source)).doesNotContain("@Service", "@Component", "@Bean", "jakarta.persistence")
                if (name.startsWith("inventory.")) assertThat(Files.readString(source)).doesNotContain(
                    "import com.duluin.ftth.workorder", "import com.duluin.ftth.customer", "import com.duluin.ftth.fulfillment")
            }
        }
    }

    private fun type(name: String): Class<*> = Class.forName(root + name)
    class RequiredContractConsumer(val dependency: Any)
    class RequiredDeploymentConsumer(val validation: InventoryWorkOrderValidationPort)
    private fun assertFields(name: String, expected: Set<String>) {
        assertThat(type(name).declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }.map { it.name })
            .describedAs(name).containsExactlyInAnyOrderElementsOf(expected)
    }
}
