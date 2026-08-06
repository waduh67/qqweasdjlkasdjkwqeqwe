package com.duluin.ftth.billing.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// Singleton global (satu baris); adapter mengambil baris pertama dari findAll.
interface PivotMasterConfigJpaRepository : JpaRepository<PivotMasterConfigJpaEntity, UUID>
