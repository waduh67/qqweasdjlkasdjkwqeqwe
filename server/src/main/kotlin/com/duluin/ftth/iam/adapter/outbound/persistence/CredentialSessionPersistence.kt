package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.common.security.CredentialSessionVerifier
import com.duluin.ftth.common.security.SessionIdentity
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Repository
class CredentialSessionPersistence(private val jdbc: TenantTransactionJdbc, manager: PlatformTransactionManager) : CredentialSessionVerifier {
    private val read = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = true
    }

    override fun isCurrent(identity: SessionIdentity): Boolean = TenantContext.runAs(identity.tenantId) {
        read.execute {
            var valid = false
            jdbc.withinTenant(identity.tenantId) { connection ->
                connection.prepareStatement("SELECT credential_version FROM app_user WHERE tenant_id=? AND id=? AND status='ACTIVE'").use { query ->
                    query.setObject(1, identity.tenantId)
                    query.setObject(2, identity.userId)
                    query.executeQuery().use { rows -> valid = rows.next() && rows.getLong(1) == identity.credentialVersion }
                }
            }
            valid
        } == true
    }
}
