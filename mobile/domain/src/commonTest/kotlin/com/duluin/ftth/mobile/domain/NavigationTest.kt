package com.duluin.ftth.mobile.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTest {
    @Test
    fun unauthenticatedAndMissingPermissionRouteSafely() {
        assertEquals(Route.SignIn, routeFor(SessionState.SignedOut, PermissionState.Unknown))
        assertEquals(Route.PermissionHelp, routeFor(SessionState.Authenticated, PermissionState.Denied))
    }
}
