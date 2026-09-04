package com.duluin.ftth.onboarding

import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportCredentialJpaEntity
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportCredentialJpaRepository
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportCredentialState
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportCredentialVault
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class CustomerImportCredentialVaultTest {
    @Test
    fun `secret survives encrypted seal and one successful consume without persistence plaintext`() {
        val repository = mock(CustomerImportCredentialJpaRepository::class.java)
        val cipher = RecordingCipher()
        var persisted: CustomerImportCredentialJpaEntity? = null
        `when`(repository.save(any())).thenAnswer { invocation ->
            persisted = invocation.arguments[0] as CustomerImportCredentialJpaEntity
            persisted
        }
        val vault = CustomerImportCredentialVault(repository, cipher)
        val handle = vault.seal("fixture-secret")!!
        `when`(repository.findForUpdate(handle.id)).thenAnswer { persisted }

        assertThat(persisted!!.ciphertext).doesNotContain("fixture-secret")
        assertThat(vault.resolve(handle)).isEqualTo("fixture-secret")
        vault.consume(handle)
        assertThat(persisted!!.state).isEqualTo(CustomerImportCredentialState.CONSUMED)
        assertThat(persisted!!.toString()).doesNotContain("fixture-secret")
    }

    private class RecordingCipher : SecretCipher {
        private val values = mutableMapOf<String, String>()
        override fun encrypt(plaintext: String): String = "cipher:${UUID.randomUUID()}".also { values[it] = plaintext }
        override fun decrypt(ciphertext: String): String = values.getValue(ciphertext)
    }
}
