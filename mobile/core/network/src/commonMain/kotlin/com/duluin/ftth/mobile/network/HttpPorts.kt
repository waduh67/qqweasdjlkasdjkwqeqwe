package com.duluin.ftth.mobile.network

import com.duluin.ftth.mobile.domain.AuthPort

interface HttpClientPort { suspend fun get(path: String): Result<String>; suspend fun post(path: String, body: String): Result<String> }
class AuthGateway(private val http: HttpClientPort) : AuthPort {
    override suspend fun refresh() = http.post("/api/auth/refresh", "{}").map { Unit }
}
