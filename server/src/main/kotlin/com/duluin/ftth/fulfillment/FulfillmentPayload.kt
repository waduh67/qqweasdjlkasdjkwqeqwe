package com.duluin.ftth.fulfillment

import java.util.UUID

private const val PAYLOAD_SEPARATOR = "|"

fun FulfillmentRequest.encode(): String = listOf(
    tenantId,
    namespace,
    operationKey,
    canonicalHash,
    source,
    targetId,
    subscriptionId ?: "",
    workOrderId ?: "",
    workOrderKind ?: "",
    approved,
    requiredEffects.sortedBy { it.name }.joinToString(",") { it.name },
    orderId ?: "",
    approvalActorId ?: "",
).joinToString(PAYLOAD_SEPARATOR)

fun String.decodeFulfillmentRequest(): FulfillmentRequest = decodeFulfillmentRequest(null, null)

fun String.decodeFulfillmentRequest(defaultTenantId: UUID?, legacyHash: String?): FulfillmentRequest {
    val values = split(PAYLOAD_SEPARATOR)
    if (values.size == 4) {
        require(defaultTenantId != null && legacyHash != null) { "FULFILLMENT_PAYLOAD_INVALID" }
        val source = FulfillmentSource.valueOf(values[2])
        return FulfillmentRequest(
            tenantId = defaultTenantId,
            namespace = values[0],
            operationKey = values[1],
            canonicalHash = legacyHash,
            source = source,
            targetId = UUID.fromString(values[3]),
            subscriptionId = null,
            workOrderId = values[3].let(UUID::fromString).takeIf { source == FulfillmentSource.WORK_ORDER },
            workOrderKind = null,
            approved = true,
            requiredEffects = if (source == FulfillmentSource.WORK_ORDER) FulfillmentEffectType.entries.toSet() else emptySet(),
        )
    }
    require(values.size == 13) { "FULFILLMENT_PAYLOAD_INVALID" }
    return FulfillmentRequest(
        tenantId = UUID.fromString(values[0]),
        namespace = values[1],
        operationKey = values[2],
        canonicalHash = values[3],
        source = FulfillmentSource.valueOf(values[4]),
        targetId = UUID.fromString(values[5]),
        subscriptionId = values[6].toUuidOrNull(),
        workOrderId = values[7].toUuidOrNull(),
        workOrderKind = values[8].ifBlank { null },
        approved = values[9].toBooleanStrict(),
        requiredEffects = values[10].split(',').filter(String::isNotBlank).mapTo(linkedSetOf(), FulfillmentEffectType::valueOf),
        orderId = values[11].toUuidOrNull(),
        approvalActorId = values[12].toUuidOrNull(),
    )
}

private fun String.toUuidOrNull(): UUID? = takeIf(String::isNotBlank)?.let(UUID::fromString)
