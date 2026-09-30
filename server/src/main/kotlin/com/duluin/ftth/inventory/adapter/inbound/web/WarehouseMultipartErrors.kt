package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import tools.jackson.module.kotlin.jacksonObjectMapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class WarehouseMultipartErrors : HandlerExceptionResolver {
    private val mapper = jacksonObjectMapper()

    override fun resolveException(request: HttpServletRequest, response: HttpServletResponse, handler: Any?, exception: Exception): ModelAndView? {
        if (!request.requestURI.removePrefix(request.contextPath).startsWith("/api/v1/warehouse/") ||
            generateSequence<Throwable>(exception) { it.cause }.none { it is MultipartException }) return null
        val code = WarehouseErrorCode.MALFORMED_REQUEST
        response.status = code.httpStatus
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        mapper.writeValue(response.writer, WarehouseError(code, code.name))
        return ModelAndView()
    }
}
