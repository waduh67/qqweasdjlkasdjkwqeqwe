package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.infrastructure.web.StrictCommandJson

internal object MaterialWorkflowJson {
    fun <T : Any> decode(body: String, type: Class<T>): T = StrictCommandJson.decode(body, type)
}
