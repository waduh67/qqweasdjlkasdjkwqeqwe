package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.cpe.application.port.outbound.AcsSettingsRepository
import com.duluin.ftth.cpe.application.port.outbound.AcsSettingsResolver
import com.duluin.ftth.cpe.config.OntAcsProperties
import com.duluin.ftth.cpe.domain.model.AcsConnectionSettings
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AcsSettingsService(
    private val repository: AcsSettingsRepository,
    private val auditor: AuditRecorder,
    private val tenantApi: TenantApi,
    private val ont: OntAcsProperties,
    @Value("\${ftth.cpe.genieacs.base-url:http://localhost:7557}") private val nbiUrl: String,
    @Value("\${ftth.cpe.genieacs.username:}") private val username: String,
    @Value("\${ftth.cpe.genieacs.password:}") private val password: String,
) : AcsSettingsResolver {
    override fun current(): AcsConnectionSettings = repository.find() ?: AcsConnectionSettings(
        AcsConnectionSettings.ENV_VERSION, nbiUrl, username, password.takeIf { it.isNotEmpty() },
        ont.publicHost.trim().takeIf { it.isNotEmpty() }?.let { "http://$it:${ont.cwmpPort}" }, false,
    )

    @Transactional
    fun update(command: UpdateAcsSettings): AcsConnectionSettings {
        val saved = repository.save(current().updated(command.nbiUrl, command.username, command.password, command.cwmpUrl))
        auditor.record(action = "platform.acs.settings.updated", entityType = "AcsSettings",
            entityId = AcsConnectionSettings.SINGLETON_ID, tenantId = tenantApi.platformTenantId(),
            detail = mapOf("version" to saved.version.toString(), "authenticated" to saved.username.isNotBlank()))
        return saved
    }
}

data class UpdateAcsSettings(val nbiUrl: String, val username: String?, val password: String?, val cwmpUrl: String)
