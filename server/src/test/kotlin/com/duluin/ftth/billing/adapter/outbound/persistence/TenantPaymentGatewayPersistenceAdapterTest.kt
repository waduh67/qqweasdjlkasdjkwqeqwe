package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID

class TenantPaymentGatewayPersistenceAdapterTest {

    private val tenantId = UuidV7.generate()
    private lateinit var jpa: FakeJpa

    @BeforeEach
    fun setUp() {
        TenantContext.set(tenantId)
        jpa = FakeJpa()
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    @Test
    fun `save mengenkripsi secret Tripay dan find memuat ulang plaintext`() {
        val gateway = TenantPaymentGateway.defaultFor(tenantId).apply {
            update(
                provider = PaymentProvider.TRIPAY,
                enabled = true,
                tripay = TripayPaymentConfig(
                    merchantCode = "merchant-1",
                    apiKey = "api-key-plain",
                    privateKey = "private-key-plain",
                    sandbox = false,
                ),
            )
        }
        val adapter = TenantPaymentGatewayPersistenceAdapter(jpa.repository, PrefixCipher)

        adapter.save(gateway)
        val raw = jpa.requireStored(gateway.id)
        val reloaded = checkNotNull(adapter.find())

        assertThat(raw.tripayMerchantCode).isEqualTo("merchant-1")
        assertThat(raw.tripayApiKey).isEqualTo("cipher:api-key-plain")
        assertThat(raw.tripayPrivateKey).isEqualTo("cipher:private-key-plain")
        assertThat(reloaded.tripay).isEqualTo(
            TripayPaymentConfig(
                merchantCode = "merchant-1",
                apiKey = "api-key-plain",
                privateKey = "private-key-plain",
                sandbox = false,
            ),
        )
    }

    private class FakeJpa {
        private val rows = linkedMapOf<UUID, TenantPaymentGatewayJpaEntity>()

        val repository: TenantPaymentGatewayJpaRepository = TenantPaymentGatewayJpaRepository::class.java.cast(
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(TenantPaymentGatewayJpaRepository::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "findAll" -> rows.values.toList()
                    "findById" -> Optional.ofNullable(rows[UUID::class.java.cast(firstArgument(arguments))])
                    "save" -> TenantPaymentGatewayJpaEntity::class.java.cast(firstArgument(arguments))
                        .also { rows[it.id] = it }
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        )

        fun requireStored(id: UUID): TenantPaymentGatewayJpaEntity = checkNotNull(rows[id])

        private fun firstArgument(arguments: Array<Any?>?): Any =
            checkNotNull(arguments?.firstOrNull()) { "JPA proxy call requires a first argument" }
    }

    private object PrefixCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "cipher:$plaintext"

        override fun decrypt(ciphertext: String): String = ciphertext.removePrefix("cipher:")
    }
}
