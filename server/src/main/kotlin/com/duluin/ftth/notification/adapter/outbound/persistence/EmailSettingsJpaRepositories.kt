package com.duluin.ftth.notification.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// Singleton global (satu baris); adapter mengambil baris pertama dari findAll.
interface PlatformEmailSettingJpaRepository : JpaRepository<PlatformEmailSettingJpaEntity, UUID>

// Paling banyak delapan baris (satu per pemicu) — findAll aman tanpa paging.
interface PlatformEmailSubjectJpaRepository : JpaRepository<PlatformEmailSubjectJpaEntity, UUID>

// Satu baris per tenant; RLS + @TenantId sudah menyaring findAll ke tenant aktif.
interface TenantEmailSettingJpaRepository : JpaRepository<TenantEmailSettingJpaEntity, UUID>

interface TenantEmailSubjectJpaRepository : JpaRepository<TenantEmailSubjectJpaEntity, UUID>
