package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.FieldHttpResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TechnicianAuthenticationTest {
    @Test fun concurrentUnauthorizedRequestsRefreshOnceAndRetainAccountIdentity() = runTest {
        val http = FieldTransport(); val credentials = FieldCredentials(); var next = 0
        val auth = TechnicianAuthentication(http, credentials, { "identity-" + next++ }, {})
        auth.login("https://ftth.example.test", "budi@example.test", "password", "123456")
        val original = auth.capture()
        val barrier = CompletableDeferred<Unit>(); var oldRequests = 0
        http.response = { call -> when {
            call.path.endsWith("/refresh") -> FieldHttpResponse(200, fieldTokens("new-access", "new-refresh"))
            call.token == "access" -> { if (++oldRequests == 2) barrier.complete(Unit); barrier.await(); FieldHttpResponse(401, "{}") }
            else -> FieldHttpResponse(200, "{}")
        } }
        awaitAll(async { auth.request("/first") }, async { auth.request("/second") })
        assertEquals(1, http.calls.count { it.path.endsWith("/refresh") })
        assertEquals(original.identity, auth.capture().identity)
        assertEquals(2, http.calls.count { it.token == "new-access" })
        val restored = TechnicianAuthentication(http, credentials, { error("restore") }, {})
        assertEquals(auth.capture(), restored.capture())
    }
    @Test fun logoutDuringReadPreventsOldSessionResponseAndPurgesDrafts() = runTest {
        val http = FieldTransport(); val credentials = FieldCredentials(); val purged = mutableListOf<String>()
        val auth = TechnicianAuthentication(http, credentials, { "identity" }, purged::add)
        auth.login("https://ftth.example.test", "budi@example.test", "password")
        http.response = { call -> if (call.path == "/read") auth.logout(); FieldHttpResponse(200, "{}") }
        assertFailsWith<IllegalArgumentException> { auth.request("/read") }
        assertNull(auth.session.value); assertNull(credentials.saved); assertEquals(listOf(USER), purged)
    }
    @Test fun unreadableCredentialsStartLoggedOut() {
        val credentials = FieldCredentials().apply { corrupt = true }
        assertNull(TechnicianAuthentication(FieldTransport(), credentials, { "key" }, {}).session.value)
        assertFalse(credentials.corrupt)
    }
}
