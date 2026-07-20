/**
 * Shared kernel: primitif domain, kontrak keamanan, tenant context, dan
 * infrastruktur lintas-module (persistence, web, config).
 *
 * <p>Dideklarasikan sebagai module <b>OPEN</b> karena memang dimaksudkan untuk
 * dipakai bebas oleh semua module — enkapsulasi sub-package tidak ditegakkan di
 * sini. Module bisnis (tenancy, iam, audit) tetap CLOSED: mereka hanya boleh
 * saling mengakses lewat tipe yang ter-expose di base package masing-masing.
 *
 * <p>Ditulis sebagai {@code package-info.java} karena Kotlin tidak mendukung
 * anotasi tingkat package.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.duluin.ftth.common;
