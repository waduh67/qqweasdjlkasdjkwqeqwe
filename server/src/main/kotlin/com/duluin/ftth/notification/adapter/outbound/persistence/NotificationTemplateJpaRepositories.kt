package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationMessageTemplateJpaRepository : JpaRepository<NotificationMessageTemplateJpaEntity, UUID> {
    fun findByNameAndLanguage(name: String, language: String): NotificationMessageTemplateJpaEntity?
    fun findAllByOrderByNameAscLanguageAsc(): List<NotificationMessageTemplateJpaEntity>
}

interface NotificationTriggerTemplateJpaRepository : JpaRepository<NotificationTriggerTemplateJpaEntity, UUID> {
    fun findByTrigger(trigger: NotificationTrigger): NotificationTriggerTemplateJpaEntity?
}
