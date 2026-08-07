package com.duluin.ftth.common.infrastructure.persistence

import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Menghapus PERMANEN seluruh data satu tenant di semua module, lalu baris `tenant`-nya.
 *
 * Data tenant tersebar di puluhan tabel ber-`tenant_id` tanpa `ON DELETE CASCADE` dari
 * `tenant`, dan mayoritas dilindungi RLS `FORCE`. Alih-alih menghardcode daftar tabel
 * (rapuh — module baru akan terlewat), tabel target ditemukan dinamis dari katalog, jadi
 * tabel baru mana pun otomatis ikut terhapus. `DELETE FROM tenant` di akhir bertindak
 * sebagai penjaga kelengkapan: bila ada FK yang terlewat, ia gagal keras dan seluruh
 * transaksi di-rollback.
 *
 * GOTCHA RLS: penghapusan HARUS berjalan di koneksi yang `app.tenant_id`-nya = tenant
 * target, kalau tidak RLS `FORCE` menyaring DELETE ke nol baris (atau, lebih buruk, ke
 * baris tenant lain yang sedang aktif di context). Karena [TenantConnectionProvider]
 * men-set GUC saat koneksi di-checkout dan tenant di-resolve saat session dibuka, kita
 * membuka transaksi BARU di dalam [TenantContext.runAs] sehingga koneksinya terikat ke
 * tenant target.
 */
@Component
class TenantEraser(txManager: PlatformTransactionManager) {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private val txTemplate = TransactionTemplate(txManager).apply {
        // Transaksi segar & mandiri: koneksi di-checkout dengan app.tenant_id = tenant target.
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** Hapus semua data [tenantId] lalu baris tenant-nya, atomik dalam satu transaksi. */
    fun erase(tenantId: UUID) {
        TenantContext.runAs(tenantId) {
            txTemplate.executeWithoutResult {
                entityManager.unwrap(Session::class.java).doWork { conn ->
                    val tables = tenantScopedTables(conn)
                    deleteAll(conn, tables, tenantId)
                    deleteTenantRow(conn, tenantId)
                }
            }
        }
    }

    /**
     * Semua BASE TABLE di skema `public` yang punya kolom `tenant_id`. `BASE TABLE`
     * mengecualikan view; `public` mengecualikan chunk `_timescaledb_internal` (DELETE
     * pada hypertable induk sudah mengalir ke chunk). Tabel `tenant` tak punya `tenant_id`
     * → otomatis terkecuali (dihapus terpisah di [deleteTenantRow]).
     */
    private fun tenantScopedTables(conn: Connection): List<String> {
        val sql = """
            SELECT c.table_name
            FROM information_schema.columns c
            JOIN information_schema.tables t
              ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            WHERE c.table_schema = 'public'
              AND c.column_name = 'tenant_id'
              AND t.table_type = 'BASE TABLE'
        """.trimIndent()
        val tables = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) tables.add(rs.getString(1))
            }
        }
        return tables
    }

    /**
     * Hapus tiap tabel dengan beberapa lintasan (multi-pass) supaya tak perlu tahu urutan
     * FK: tabel yang deletenya diblok FK (SQLState 23503 — termasuk NO ACTION yang dicek di
     * akhir statement) di-rollback ke savepoint dan dicoba lagi di lintasan berikutnya,
     * setelah anak-anaknya bersih. Cascade intra-module (mis. `customer`→`subscription`,
     * `app_user`→`user_role`/`refresh_token`) menghabiskan banyak tabel di lintasan pertama.
     */
    private fun deleteAll(conn: Connection, tables: List<String>, tenantId: UUID) {
        val remaining = tables.toMutableList()
        while (remaining.isNotEmpty()) {
            var progressed = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val table = iterator.next()
                val savepoint = conn.setSavepoint()
                try {
                    // Nama tabel berasal dari katalog (tepercaya) → aman diinterpolasi;
                    // tenant_id selalu lewat parameter.
                    conn.prepareStatement("""DELETE FROM "$table" WHERE tenant_id = ?""").use { st ->
                        st.setObject(1, tenantId)
                        st.executeUpdate()
                    }
                    conn.releaseSavepoint(savepoint)
                    iterator.remove()
                    progressed = true
                } catch (e: SQLException) {
                    if (e.sqlState == FK_VIOLATION) {
                        conn.rollback(savepoint) // masih ada anak yang mereferensi — coba lagi nanti.
                    } else {
                        throw e
                    }
                }
            }
            check(progressed) { "Tak bisa menyelesaikan urutan FK saat menghapus tenant; tersisa: $remaining" }
        }
    }

    private fun deleteTenantRow(conn: Connection, tenantId: UUID) {
        conn.prepareStatement("DELETE FROM tenant WHERE id = ?").use { st ->
            st.setObject(1, tenantId)
            st.executeUpdate()
        }
    }

    private companion object {
        /** `foreign_key_violation` — anak masih mereferensi baris yang hendak dihapus. */
        const val FK_VIOLATION = "23503"
    }
}
