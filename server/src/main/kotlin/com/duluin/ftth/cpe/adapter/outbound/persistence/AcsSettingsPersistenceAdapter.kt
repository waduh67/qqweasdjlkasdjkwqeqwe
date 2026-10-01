package com.duluin.ftth.cpe.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.cpe.application.port.outbound.AcsSettingsRepository
import com.duluin.ftth.cpe.domain.model.AcsConnectionSettings
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Entity
@Table(name = "platform_acs_setting")
class AcsSettingJpaEntity(
    @Column(nullable = false) var version: UUID,
    @Column(name = "nbi_url", nullable = false, length = 2048) var nbiUrl: String,
    @Column(nullable = false, length = 255) var username: String,
    @Column(columnDefinition = "text") var password: String?,
    @Column(name = "cwmp_url", nullable = false, length = 2048) var cwmpUrl: String,
) : BaseJpaEntity(AcsConnectionSettings.SINGLETON_ID)

interface AcsSettingJpaRepository : JpaRepository<AcsSettingJpaEntity, UUID>

@Component
class AcsSettingsPersistenceAdapter(private val jpa: AcsSettingJpaRepository, private val cipher: SecretCipher) : AcsSettingsRepository {
    override fun find(): AcsConnectionSettings? = jpa.findById(AcsConnectionSettings.SINGLETON_ID).orElse(null)?.toDomain()

    override fun save(settings: AcsConnectionSettings): AcsConnectionSettings {
        val encrypted = settings.password?.let(cipher::encrypt)
        val entity = jpa.findById(AcsConnectionSettings.SINGLETON_ID).orElse(null)?.apply {
            version = settings.version
            nbiUrl = settings.nbiUrl
            username = settings.username
            password = encrypted
            cwmpUrl = requireNotNull(settings.cwmpUrl)
        } ?: AcsSettingJpaEntity(settings.version, settings.nbiUrl, settings.username, encrypted, requireNotNull(settings.cwmpUrl))
        return jpa.saveAndFlush(entity).toDomain()
    }

    private fun AcsSettingJpaEntity.toDomain() = AcsConnectionSettings(version, nbiUrl, username, password?.let(cipher::decrypt), cwmpUrl, true)
}
