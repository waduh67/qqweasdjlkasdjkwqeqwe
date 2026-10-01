package com.duluin.ftth.cpe

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.infrastructure.security.AesGcmSecretCipher
import com.duluin.ftth.cpe.adapter.inbound.web.PlatformAcsSettingsController
import com.duluin.ftth.cpe.adapter.outbound.persistence.AcsSettingJpaEntity
import com.duluin.ftth.cpe.adapter.outbound.persistence.AcsSettingJpaRepository
import com.duluin.ftth.cpe.adapter.outbound.persistence.AcsSettingsPersistenceAdapter
import com.duluin.ftth.cpe.application.port.outbound.AcsConnectionProbe
import com.duluin.ftth.cpe.application.port.outbound.AcsProbe
import com.duluin.ftth.cpe.application.port.outbound.AcsSettingsRepository
import com.duluin.ftth.cpe.application.service.AcsSettingsService
import com.duluin.ftth.cpe.application.service.UpdateAcsSettings
import com.duluin.ftth.cpe.config.OntAcsProperties
import com.duluin.ftth.cpe.domain.model.AcsConnectionSettings
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Optional
import java.util.UUID

class AcsSettingsTest {
    private fun initial() = AcsConnectionSettings(AcsConnectionSettings.ENV_VERSION, "http://env.test:7557", "env-user", "env-secret", null, false)

    @Test
    fun `URL and authentication validation rejects invalid settings without changing the original`() {
        val before = initial()
        for (url in listOf("ftp://acs.test", "https://user:secret@acs.test", "https://acs.test?secret=x", "https://acs.test#fragment", "http://acs.test:0", "http://acs.test:65536", "acs.test", "http://")) {
            assertThatThrownBy { before.updated(url, "", "", "http://cwmp.test:7547") }.isInstanceOf(ValidationException::class.java)
            assertThatThrownBy { before.updated("https://api.test", "", "", url) }.isInstanceOf(ValidationException::class.java)
        }
        for (user in listOf("a:b", "a\nb", "a".repeat(256))) {
            assertThatThrownBy { before.updated("https://api.test", user, "secret", "http://cwmp.test:7547") }.isInstanceOf(ValidationException::class.java)
        }
        assertThatThrownBy { before.updated("https://api.test", "api", "x".repeat(513), "http://cwmp.test:7547") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { AcsConnectionSettings(UUID.randomUUID(), "http://api.test", "", null, null, false).updated("https://api.test", "api", "", "http://cwmp.test:7547") }.isInstanceOf(ValidationException::class.java)
        assertThat(before.password).isEqualTo("env-secret")
        assertThat(before.toString()).doesNotContain("env-secret")
    }

    @Test
    fun `saved whole record takes precedence across service reload and blank password preserves the secret`() {
        var stored: AcsConnectionSettings? = null
        val repository = object : AcsSettingsRepository {
            override fun find() = stored
            override fun save(settings: AcsConnectionSettings) = settings.also { stored = it }
        }
        val tenant = Mockito.mock(TenantApi::class.java)
        Mockito.`when`(tenant.platformTenantId()).thenReturn(UUID.randomUUID())
        val auditor = Mockito.mock(AuditRecorder::class.java)
        fun service(env: String) = AcsSettingsService(repository, auditor, tenant, OntAcsProperties(publicHost = "env-cwmp.test"), env, "env-user", "env-secret")
        val first = service("http://env.test:7557")
        assertThat(first.current().persisted).isFalse()
        assertThat(first.current().cwmpUrl).isEqualTo("http://env-cwmp.test:7547")
        val saved = first.update(UpdateAcsSettings(" https://api.test/nbi/ ", " api-user ", "saved-secret", "https://cwmp.test/tr069"))
        assertThat(saved.nbiUrl).isEqualTo("https://api.test/nbi")
        val reloaded = service("http://different-env.test:7557")
        assertThat(reloaded.current().version).isEqualTo(saved.version)
        val edited = reloaded.update(UpdateAcsSettings("https://new-api.test", "api-user", "", "http://cwmp.test:7547/"))
        assertThat(edited.password).isEqualTo("saved-secret")
        assertThat(edited.version).isNotEqualTo(saved.version)
        val disabled = reloaded.update(UpdateAcsSettings("http://private-api.test:7557", "", null, "http://cwmp.test:7547/"))
        assertThat(disabled.username).isEmpty()
        assertThat(disabled.password).isEqualTo("saved-secret")
        var tested: AcsConnectionSettings? = null
        val controller = PlatformAcsSettingsController(reloaded, AcsConnectionProbe { tested = it; AcsProbe(true, 1, null) })
        val json = jacksonObjectMapper().writeValueAsString(controller.get())
        assertThat(json).contains("\"passwordSet\":true").doesNotContain("saved-secret", "env-secret", "\"password\":")
        controller.test()
        assertThat(tested).isSameAs(disabled)
        Mockito.mockingDetails(auditor).invocations.forEach { invocation ->
            assertThat(invocation.arguments.contentToString()).doesNotContain("saved-secret", "env-secret")
        }
    }

    @Test
    fun `password is encrypted at rest and survives a new adapter while corrupted ciphertext fails closed`() {
        val jpa = Mockito.mock(AcsSettingJpaRepository::class.java)
        var row: AcsSettingJpaEntity? = null
        Mockito.`when`(jpa.findById(AcsConnectionSettings.SINGLETON_ID)).thenAnswer { Optional.ofNullable(row) }
        Mockito.`when`(jpa.saveAndFlush(any(AcsSettingJpaEntity::class.java))).thenAnswer { call -> call.getArgument<AcsSettingJpaEntity>(0).also { row = it } }
        val properties = SecurityProperties("test-jwt-secret-longer-than-thirty-two-bytes", "test-encryption-secret-longer-than-thirty-two-bytes")
        val adapter = AcsSettingsPersistenceAdapter(jpa, AesGcmSecretCipher(properties))
        val settings = initial().updated("https://api.test", "api", "encrypted-secret", "http://cwmp.test:7547")
        adapter.save(settings)
        assertThat(row?.id).isEqualTo(AcsConnectionSettings.SINGLETON_ID)
        assertThat(row?.password).startsWith("v1:").doesNotContain("encrypted-secret")
        val reloaded = AcsSettingsPersistenceAdapter(jpa, AesGcmSecretCipher(properties)).find()
        assertThat(reloaded?.password).isEqualTo("encrypted-secret")
        assertThat(reloaded?.version).isEqualTo(settings.version)
        row?.password = "v1:corrupt"
        assertThatThrownBy { adapter.find() }.isInstanceOf(Exception::class.java)
    }
}
