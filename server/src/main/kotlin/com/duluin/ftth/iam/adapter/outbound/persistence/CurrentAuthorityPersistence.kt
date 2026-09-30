package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.security.*
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager as Transactions
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

@Service
class CurrentAuthorityPersistence(
    private val entityManager: EntityManager,
    private val users: CurrentUserProvider,
) : CurrentAuthorityApi, com.duluin.ftth.iam.DeliveryAuthorityApi {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockCurrent(): CurrentAuthority {
        val user = users.currentOrNull() ?: throw com.duluin.ftth.common.domain.error.AuthenticationException("Authentication required")
        return lockActor(SessionIdentity(user.tenantId, user.userId, user.sessionId))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockActor(identity: SessionIdentity): CurrentAuthority {
        val user = identity
        if (user.tenantId != TenantContext.tenantId()) denied()
        val transaction = lock(false)
        entityManager.flush()
        val platform = query("SELECT platform_admin FROM app_user WHERE tenant_id=? AND id=? AND status='ACTIVE'",
            user.tenantId, user.userId) { it.getBoolean(1) }.singleOrNull() ?: denied()
        val roles = query("""SELECT role.id FROM user_role link JOIN role ON role.id=link.role_id
            WHERE link.user_id=? AND role.tenant_id=?""", user.userId, user.tenantId) { it.getObject(1, UUID::class.java) }.toSet()
        val permissions = query("""SELECT DISTINCT permission.code FROM user_role link
            JOIN role ON role.id=link.role_id AND role.tenant_id=?
            JOIN role_permission grant_row ON grant_row.role_id=role.id
            JOIN permission ON permission.id=grant_row.permission_id
            WHERE link.user_id=? AND permission.active AND (NOT permission.platform_only OR ?)""",
            user.tenantId, user.userId, platform) { it.getString(1) }.toSet()
        val areas = query("""SELECT area.id FROM user_area link JOIN area ON area.id=link.area_id
            WHERE link.user_id=? AND area.tenant_id=?""", user.userId, user.tenantId) { it.getObject(1, UUID::class.java) }.toSet()
        val epoch = transaction.epoch
        val generation = transaction.generation
        val fence = object : AuthorityFence {
            override val identity = identity
            override val epoch = epoch
            override fun assertHeld() {
                transaction.assertHeld()
                check(transaction.epoch == epoch && transaction.generation == generation) { "Authority changed after snapshot" }
            }
        }
        return CurrentAuthority(fence, roles, permissions,
            if (platform) AuthorityScope.Unrestricted else AuthorityScope.Restricted(areas), platform)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockForChange(): AuthorityChangeFence {
        val transaction = lock(true)
        return object : AuthorityChangeFence {
            override val tenantId get() = transaction.tenant
            override val epoch get() = transaction.epoch
            override fun assertHeld() = transaction.assertHeld()
            override fun incrementEpoch(): Long {
                assertHeld()
                transaction.generation++
                if (CatalogAuthorityFence.incremented(tenantId)) transaction.incremented = true
                if (!transaction.incremented) {
                    transaction.epoch = query("""UPDATE iam_authorization_epoch SET epoch=epoch+1,
                        revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? RETURNING epoch""",
                        tenantId) { it.getLong(1) }.single()
                    transaction.incremented = true
                }
                return transaction.epoch
            }
        }
    }

    private fun lock(exclusive: Boolean): AuthorityTransaction {
        check(Transactions.isActualTransactionActive() && !Transactions.isCurrentTransactionReadOnly())
        val tenant = TenantContext.tenantId()
        val prior = Transactions.getSynchronizations().filterIsInstance<AuthorityTransaction>().singleOrNull()
        if (prior != null) {
            prior.assertHeld()
            check(!exclusive || prior.exclusive) { "Cannot upgrade a minted authority snapshot" }
            return prior
        }
        val mode = if (exclusive) "FOR UPDATE" else "FOR SHARE"
        query("SELECT pg_advisory_xact_lock_shared(hashtextextended(current_schema()||':iam.catalog',0))") { Unit }
        val epoch = query("SELECT epoch FROM iam_authorization_epoch WHERE tenant_id=? $mode", tenant) { it.getLong(1) }
            .singleOrNull() ?: denied()
        return AuthorityTransaction(tenant, epoch, exclusive).also(Transactions::registerSynchronization)
    }

    private fun <T> query(sql: String, vararg values: Any, read: (ResultSet) -> T): List<T> =
        entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            check(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
            }
        }

    private fun denied(): Nothing = throw AccessDeniedException("Current authority is unavailable")

    private class AuthorityTransaction(val tenant: UUID, var epoch: Long, val exclusive: Boolean) : TenantAuthorityTransaction {
        private val resources = Transactions.getResourceMap().toMap()
        private val thread = Thread.currentThread()
        private var active = true
        var incremented = false
        var generation = 0L
        fun assertHeld() {
            check(active && Thread.currentThread() === thread && TenantContext.tenantId() == tenant &&
                Transactions.isActualTransactionActive() && Transactions.getSynchronizations().any { it === this } &&
                resources.all { (key, value) -> Transactions.getResource(key) === value }) {
                "Authority fence is no longer held in its originating transaction"
            }
        }
        override fun afterCompletion(status: Int) { active = false }
    }
}
