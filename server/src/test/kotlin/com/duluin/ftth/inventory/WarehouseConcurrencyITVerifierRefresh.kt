package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.application.port.inbound.AssignAccessCommand
import com.duluin.ftth.iam.application.service.AuthenticationService
import com.duluin.ftth.iam.application.service.Tokens
import com.duluin.ftth.iam.application.service.UserService
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseConcurrencyITVerifierRefresh {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }

    private fun token(fixture: WarehousePostingFixture): String = UUID.randomUUID().toString().also { raw -> fixture.transaction {
        sql("INSERT INTO refresh_token(id,tenant_id,user_id,token_hash,expires_at) VALUES ('${UUID.randomUUID()}','$tenant','$actor','${Tokens.sha256(raw)}',clock_timestamp()+interval '1 hour')")
    } }
    private fun WarehousePostingFixture.waitForRefreshes(count: Int) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        var blocked=0
        while(blocked<count && System.nanoTime()<deadline) {
            sql("SELECT pg_stat_clear_snapshot()")
            blocked=scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%iam_authorization_epoch%'").toInt()
            Thread.yield()
        }
        assertThat(blocked).isGreaterThanOrEqualTo(count)
    }
    private fun WarehousePostingFixture.active(): Int = scalar("SELECT count(*) FROM refresh_token WHERE tenant_id='$tenant' AND user_id='$actor' AND revoked_at IS NULL AND expires_at>clock_timestamp()").toInt()

    @ParameterizedTest @ValueSource(strings=["ACCESS","LOGOUT"])
    fun `AV6-02 revocation while refresh waits must not issue replacement`(mode: String) {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val raw=token(fixture)
        val auth=context.getBean(AuthenticationService::class.java)
        val pool=Executors.newSingleThreadExecutor()
        try {
            lateinit var refreshing: java.util.concurrent.Future<Throwable?>
            fixture.transaction {
                context.getBean(CurrentAuthorityApi::class.java).lockForChange()
                refreshing=pool.submit<Throwable?> { catchThrowable { auth.refresh(raw) } }
                waitForRefreshes(1)
                if(mode=="ACCESS") context.getBean(UserService::class.java).assignAccess(actor,AssignAccessCommand(emptySet(),emptySet()))
                else auth.logout(raw)
            }
            assertThat(refreshing.get(20,TimeUnit.SECONDS)).isInstanceOf(AuthenticationException::class.java)
            fixture.transaction { assertThat(active()).isZero(); assertThat(scalar("SELECT count(*) FROM refresh_token WHERE user_id='$actor'")).isEqualTo("1") }
        } finally { pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }

    @Test fun `duplicate refresh waiting on fence produces one active replacement`() {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val raw=token(fixture); val auth=context.getBean(AuthenticationService::class.java)
        val pool=Executors.newFixedThreadPool(2)
        try {
            lateinit var requests: List<java.util.concurrent.Future<Throwable?>>
            fixture.transaction {
                context.getBean(CurrentAuthorityApi::class.java).lockForChange()
                requests=(1..2).map { pool.submit<Throwable?> { catchThrowable { auth.refresh(raw) } } }
                waitForRefreshes(2)
            }
            val outcomes=requests.map { it.get(20,TimeUnit.SECONDS) }
            assertThat(outcomes.count { it==null }).isEqualTo(1)
            assertThat(outcomes.filterNotNull()).allMatch { it is AuthenticationException }
            fixture.transaction { assertThat(active()).isEqualTo(1); assertThat(scalar("SELECT count(*) FROM refresh_token WHERE user_id='$actor'")).isEqualTo("2") }
        } finally { pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }

    @Test fun `normal refresh rotates once and rollback restores original token`() {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val raw=token(fixture); val auth=context.getBean(AuthenticationService::class.java)
        assertThatThrownBy { fixture.transaction { auth.refresh(raw); error("rollback rotation") } }.isInstanceOf(IllegalStateException::class.java)
        fixture.transaction { assertThat(active()).isEqualTo(1); assertThat(scalar("SELECT count(*) FROM refresh_token WHERE user_id='$actor'")).isEqualTo("1") }
        val rotated=auth.refresh(raw)
        assertThat(rotated.refreshToken).isNotEqualTo(raw)
        assertThatThrownBy { auth.refresh(raw) }.isInstanceOf(AuthenticationException::class.java)
        fixture.transaction { assertThat(active()).isEqualTo(1) }
        auth.logout(rotated.refreshToken)
        fixture.transaction { assertThat(active()).isZero() }
    }

    @Test fun `foreign tenant hash and expired token cannot rotate`() {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val other=WarehousePostingFixture(context)
        val raw=token(fixture)
        assertThatThrownBy { other.transaction {
            context.getBean(com.duluin.ftth.iam.application.service.TenantScopedAuthenticator::class.java).rotateAndIssue(Tokens.sha256(raw))
        } }.isInstanceOf(AuthenticationException::class.java)
        fixture.transaction {
            assertThat(active()).isEqualTo(1)
            sql("UPDATE refresh_token SET expires_at=clock_timestamp() WHERE token_hash='${Tokens.sha256(raw)}'")
        }
        assertThatThrownBy { context.getBean(AuthenticationService::class.java).refresh(raw) }.isInstanceOf(AuthenticationException::class.java)
        fixture.transaction { assertThat(active()).isZero(); assertThat(scalar("SELECT count(*) FROM refresh_token WHERE user_id='$actor'")).isEqualTo("1") }
    }
}
