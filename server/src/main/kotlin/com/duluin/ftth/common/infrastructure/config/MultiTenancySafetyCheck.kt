package com.duluin.ftth.common.infrastructure.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Menggagalkan startup bila `spring.jpa.open-in-view` aktif.
 *
 * Ini bukan sekadar soal performa: dengan open-in-view, EntityManager dibuka di
 * awal request — SEBELUM filter autentikasi memasang tenant context. Sesi
 * Hibernate itu terlanjur terikat pada tenant sentinel, sehingga seluruh query
 * request tersebut ter-scope ke tenant yang salah. Kegagalannya SENYAP (tidak
 * ada error, hanya data kosong/keliru), jadi lebih baik ditolak sejak startup.
 */
@Component
@ConfigurationProperties(prefix = "spring.jpa")
class MultiTenancySafetyCheck {

    var openInView: Boolean = true

    @PostConstruct
    fun verify() {
        check(!openInView) {
            "spring.jpa.open-in-view harus false: multi-tenancy mengandalkan sesi Hibernate " +
                "yang dibuka SETELAH tenant context ter-set. Bila aktif, query akan ter-scope " +
                "ke tenant yang salah tanpa error."
        }
    }
}
