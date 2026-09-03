package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.inbound.UpdatePaymentGatewaySettingsCommand
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.application.service.PaymentGatewaySettingsService
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.common.tenant.TenantContext
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Menguji sisi service setelan gateway yang menyentuh object storage: unggah/hapus gambar QRIS.
 * Pakai fake in-memory (repository + storage) + [TenantContext] disetel manual — tanpa Spring/DB.
 */
class PaymentGatewaySettingsServiceTest {

    private val tenantId = UuidV7.generate()

    private lateinit var repository: FakeRepository
    private lateinit var storage: FakeStorage
    private lateinit var service: PaymentGatewaySettingsService

    @BeforeEach
    fun setUp() {
        TenantContext.set(tenantId)
        repository = FakeRepository()
        storage = FakeStorage()
        service = PaymentGatewaySettingsService(
            repository = repository,
            auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
            storage = storage,
        )
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    @Test
    fun `uploadQrisImage menaruh byte ke storage dan mengeset penanda`() {
        val view = service.uploadQrisImage("image/png", byteArrayOf(1, 2, 3))

        assertThat(view.qrisImageSet).isTrue()
        val saved = checkNotNull(repository.find())
        assertThat(saved.qrisStorageKey).isEqualTo("$tenantId/billing/gateway/qris")
        assertThat(saved.qrisContentType).isEqualTo("image/png")
        assertThat(storage.objects).containsKey("$tenantId/billing/gateway/qris")
    }

    @Test
    fun `uploadQrisImage menolak berkas bukan gambar`() {
        assertThatThrownBy { service.uploadQrisImage("application/pdf", byteArrayOf(1)) }
            .isInstanceOf(ValidationException::class.java)
        assertThat(storage.objects).isEmpty()
    }

    @Test
    fun `deleteQrisImage menghapus byte dari storage dan menihilkan kolom`() {
        service.uploadQrisImage("image/png", byteArrayOf(1, 2, 3))

        val view = service.deleteQrisImage()

        assertThat(view.qrisImageSet).isFalse()
        val saved = checkNotNull(repository.find())
        assertThat(saved.qrisStorageKey).isNull()
        assertThat(saved.qrisContentType).isNull()
        assertThat(storage.objects).isEmpty()
    }

    @Test
    fun `getQrisImage mengembalikan byte tersimpan lalu null setelah dihapus`() {
        service.uploadQrisImage("image/png", byteArrayOf(9, 8, 7))
        assertThat(service.getQrisImage()?.bytes).containsExactly(9, 8, 7)

        service.deleteQrisImage()
        assertThat(service.getQrisImage()).isNull()
    }

    @Test
    fun `update TRIPAY mempertahankan secret kosong dan view tidak menserialkan credential`() {
        val stored = TenantPaymentGateway.defaultFor(tenantId).apply {
            update(
                provider = PaymentProvider.TRIPAY,
                enabled = true,
                tripay = TripayPaymentConfig(
                    merchantCode = "merchant-existing",
                    apiKey = "api-key-existing",
                    privateKey = "private-key-existing",
                    sandbox = false,
                ),
            )
        }
        repository.save(stored)

        val view = service.update(
            UpdatePaymentGatewaySettingsCommand(
                provider = PaymentProvider.TRIPAY,
                enabled = true,
                manualTransferEnabled = false,
                bankName = null,
                accountNumber = null,
                accountHolder = null,
                manualQrisEnabled = false,
                tripayMerchantCode = "merchant-current",
                tripayApiKey = "   ",
                tripayPrivateKey = null,
                tripaySandbox = true,
            ),
        )

        assertThat(checkNotNull(repository.find()).tripay).isEqualTo(
            TripayPaymentConfig(
                merchantCode = "merchant-current",
                apiKey = "api-key-existing",
                privateKey = "private-key-existing",
                sandbox = true,
            ),
        )
        assertThat(view.tripayApiKeySet).isTrue()
        assertThat(view.tripayPrivateKeySet).isTrue()
        val serialized = ObjectMapper().writeValueAsString(view)
        assertThat(serialized)
            .contains("\"tripayApiKeySet\":true", "\"tripayPrivateKeySet\":true")
            .doesNotContain("api-key-existing", "private-key-existing")
    }

    @Test
    fun `update TRIPAY aktif menolak credential efektif yang tidak lengkap`() {
        assertThatThrownBy {
            service.update(
                UpdatePaymentGatewaySettingsCommand(
                    provider = PaymentProvider.TRIPAY,
                    enabled = true,
                    manualTransferEnabled = false,
                    bankName = null,
                    accountNumber = null,
                    accountHolder = null,
                    manualQrisEnabled = false,
                    tripayMerchantCode = "merchant-only",
                    tripayApiKey = " ",
                    tripayPrivateKey = null,
                    tripaySandbox = true,
                ),
            )
        }.isInstanceOf(ValidationException::class.java)
    }

    // --- fakes ---

    private class FakeRepository : TenantPaymentGatewayRepository {
        private var row: TenantPaymentGateway? = null
        override fun find(): TenantPaymentGateway? = row
        override fun save(settings: TenantPaymentGateway): TenantPaymentGateway = settings.also { row = it }
    }

    // (tak ada lagi PaywuzMethodDirectory — kredensial gateway dibuang dari service)

    private class FakeStorage : ObjectStorage {
        val objects = ConcurrentHashMap<String, StoredObject>()
        override fun put(key: String, contentType: String, bytes: ByteArray) {
            objects[key] = StoredObject(contentType, bytes.copyOf())
        }

        override fun get(key: String): StoredObject =
            objects[key] ?: error("Objek $key tidak ada")

        override fun delete(key: String) {
            objects.remove(key)
        }
    }

    private object NoUser : CurrentUserProvider {
        override fun currentOrNull(): AuthenticatedUser? = null
    }
}
