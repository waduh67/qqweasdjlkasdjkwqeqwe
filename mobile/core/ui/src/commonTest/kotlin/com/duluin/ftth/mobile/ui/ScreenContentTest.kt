package com.duluin.ftth.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenContentTest {
    @Test
    fun statePresentationSuppliesCopySeverityAndRetryAffordance() {
        assertEquals("Memuat data lapangan...", ScreenContent.Loading.presentation().message)
        assertEquals("Belum ada pekerjaan untuk ditampilkan.", ScreenContent.Empty.presentation().message)
        assertFalse(ScreenContent.Offline("offline").presentation().critical)
        assertTrue(ScreenContent.Error("gagal").presentation().critical)
        assertEquals("Coba lagi", ScreenContent.Error("gagal").presentation().retryLabel)
        assertTrue(ScreenContent.Conflict("konflik").presentation().critical)
        assertEquals("Muat ulang perubahan", ScreenContent.Conflict("konflik").presentation().retryLabel)
        assertTrue(ScreenContent.PermissionDenied("izin").presentation().critical)
        assertNull(ScreenContent.PermissionDenied("izin").presentation().retryLabel)
    }
}
