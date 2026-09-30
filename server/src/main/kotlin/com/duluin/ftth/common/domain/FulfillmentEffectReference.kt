package com.duluin.ftth.common.domain

import java.util.UUID

data class FulfillmentEffectReference(val approvalId: UUID, val namespace: String, val operationKey: String, val payloadHash: String)
