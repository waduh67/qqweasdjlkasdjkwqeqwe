package com.duluin.ftth.tenancy.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.application.port.outbound.TenantDataArchivePort
import com.duluin.ftth.tenancy.application.port.outbound.TenantExportReport
import com.duluin.ftth.tenancy.application.port.outbound.TenantExportTable
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ekspor data tenant sebagai ZIP berisi satu CSV per tabel.
 *
 * Dibaca langsung lewat JDBC, bukan lewat repository tiap module. Alasannya sama dengan
 * penghapusan tenant: yang dituntut di sini KELENGKAPAN, dan satu-satunya daftar tabel yang
 * tak pernah basi adalah katalog database itu sendiri. Melewati model domain juga membuat
 * arsip merekam apa adanya — termasuk kolom yang belum dipetakan ke entitas mana pun.
 *
 * GOTCHA RLS: pembacaan HARUS berjalan di koneksi yang `app.tenant_id`-nya = tenant target,
 * kalau tidak RLS `FORCE` mengembalikan nol baris. Karena GUC dipasang saat koneksi
 * di-checkout dan tenant di-resolve saat session Hibernate dibuka, transaksi BARU dibuka di
 * dalam [TenantContext.runAs] (pola yang sama dipakai penghapusan tenant).
 */
@Component
class JdbcTenantDataArchiveAdapter(txManager: PlatformTransactionManager) : TenantDataArchivePort {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private val txTemplate = TransactionTemplate(txManager).apply {
        // Transaksi segar & mandiri: koneksinya di-checkout dengan app.tenant_id = tenant target.
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = true
    }

    override fun writeArchive(tenantId: UUID, target: OutputStream): TenantExportReport =
        TenantContext.runAs(tenantId) {
            txTemplate.execute { writeAll(tenantId, target) }!!
        }

    private fun writeAll(tenantId: UUID, target: OutputStream): TenantExportReport {
        val zip = ZipOutputStream(target, Charsets.UTF_8)
        // Satu writer untuk seluruh arsip: ditutup akan ikut menutup ZIP (dan aliran respons),
        // jadi cukup di-flush sebelum tiap entry ditutup.
        val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
        val exported = mutableListOf<TenantExportTable>()
        val skipped = linkedMapOf<String, String>()

        entityManager.unwrap(Session::class.java).doWork { conn ->
            for (table in tenantScopedTables(conn)) {
                val reason = SKIPPED_TABLES[table]
                if (reason != null) {
                    skipped[table] = reason
                    continue
                }
                zip.putNextEntry(ZipEntry("data/$table.csv"))
                exported += dumpTable(conn, table, tenantId, writer)
                writer.flush()
                zip.closeEntry()
            }
        }

        val report = TenantExportReport(Instant.now(), exported, skipped)
        zip.putNextEntry(ZipEntry(README_ENTRY))
        writer.write(readme(report))
        writer.flush()
        zip.closeEntry()
        // finish(), bukan close(): pemilik aliran respons yang menutupnya.
        zip.finish()
        return report
    }

    /**
     * Semua BASE TABLE di skema `public` yang punya kolom `tenant_id` — sasaran yang sama
     * persis dengan penghapusan tenant, sehingga "yang bisa dihapus" dan "yang bisa dibawa
     * pulang" tak pernah berbeda. `BASE TABLE` mengecualikan view; `public` mengecualikan
     * chunk hypertable di `_timescaledb_internal` (isinya sudah terwakili tabel induk).
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
            ORDER BY c.table_name
        """.trimIndent()
        val tables = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) tables.add(rs.getString(1))
            }
        }
        return tables
    }

    private fun dumpTable(conn: Connection, table: String, tenantId: UUID, out: Writer): TenantExportTable {
        val columns = columnsOf(conn, table)
        val select = columns.joinToString(", ") { it.selectExpression() }
        var rows = 0L
        // Nama tabel & kolom berasal dari katalog (tepercaya) → aman diinterpolasi;
        // tenant_id selalu lewat parameter.
        conn.prepareStatement("""SELECT $select FROM "$table" WHERE tenant_id = ?""").use { st ->
            // Streaming per-batch: tabel besar (audit, tagihan) tak boleh dimuat utuh ke heap
            // hanya untuk diteruskan ke soket. Butuh autoCommit=false — dijamin oleh transaksi.
            st.fetchSize = FETCH_SIZE
            st.setObject(1, tenantId)
            st.executeQuery().use { rs ->
                out.append(columns.joinToString(SEPARATOR) { csvEscape(it.name) }).append(ROW_END)
                while (rs.next()) {
                    out.append(
                        columns
                            .mapIndexed { index, column -> column.render(rs.getString(index + 1)) }
                            .joinToString(SEPARATOR) { csvEscape(it) },
                    ).append(ROW_END)
                    rows++
                }
            }
        }
        return TenantExportTable(table, rows, columns.filter { it.secret }.map { it.name })
    }

    private fun columnsOf(conn: Connection, table: String): List<ExportColumn> {
        val sql = """
            SELECT column_name, udt_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position
        """.trimIndent()
        val columns = mutableListOf<ExportColumn>()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, table)
            st.executeQuery().use { rs ->
                while (rs.next()) columns.add(ExportColumn(rs.getString(1), rs.getString(2)))
            }
        }
        return columns
    }

    private companion object {
        const val FETCH_SIZE = 500
        const val SEPARATOR = ","

        /** CRLF sesuai RFC 4180 — yang dibaca benar oleh Excel maupun perkakas Unix. */
        const val ROW_END = "\r\n"
        const val README_ENTRY = "BACA-DULU.txt"

        /**
         * Tabel yang sengaja TIDAK ikut, beserta alasannya (ditulis juga di dalam arsip —
         * pengecualian yang diam-diam sama saja dengan arsip yang bohong tentang isinya).
         * Kuncinya nama tabel; yang tak ada di database tinggal tak pernah cocok.
         */
        val SKIPPED_TABLES = mapOf(
            "accounting_record" to
                "cuplikan counter sesi tiap 30 detik (retensi 90 hari) — puluhan juta baris deret waktu " +
                "yang tak punya arti di luar sistem ini; rekap pemakaiannya ada di tagihan",
            "onu_metric" to
                "cuplikan redaman/suhu ONU tiap polling — sama, telemetri mentah, bukan data pelanggan",
            "refresh_token" to
                "token sesi login yang mati begitu tenant berpindah sistem",
        )
    }

    /** Isi BACA-DULU.txt: menjelaskan format, sekaligus mempertanggungjawabkan apa yang tak ikut. */
    private fun readme(report: TenantExportReport): String = buildString {
        appendLine("Arsip data tenant — NetOps Console")
        appendLine("Dibuat: ${report.generatedAt}")
        appendLine()
        appendLine("Isi arsip ini adalah seluruh data milik tenant Anda: satu berkas CSV per tabel")
        appendLine("di folder data/. Baris pertama tiap berkas adalah nama kolom. Format mengikuti")
        appendLine("RFC 4180 (pemisah koma; nilai dibungkus kutip bila memuat koma, kutip, atau baris")
        appendLine("baru) dengan pengodean UTF-8. Sel kosong berarti NULL.")
        appendLine()
        appendLine("Kolom lokasi (tipe geometry) ditulis sebagai EWKT, misalnya")
        appendLine("  SRID=4326;POINT(107.6 -6.9)")
        appendLine()
        appendLine("Tabel yang diekspor (${report.tables.size} tabel, ${report.rowCount} baris):")
        report.tables.forEach { appendLine("  ${it.name} — ${it.rows} baris") }

        val redacted = report.tables.flatMap { table -> table.redactedColumns.map { "${table.name}.$it" } }
        if (redacted.isNotEmpty()) {
            appendLine()
            appendLine("Kolom yang DISUNTING (isinya diganti \"$REDACTED\"):")
            redacted.forEach { appendLine("  $it") }
            appendLine("Kolom-kolom itu berisi kata sandi, kunci, atau token. Sebagian pun tersimpan")
            appendLine("terenkripsi sehingga tak berarti apa-apa di luar sistem ini.")
        }

        if (report.skipped.isNotEmpty()) {
            appendLine()
            appendLine("Tabel yang TIDAK diekspor:")
            report.skipped.forEach { (table, reason) -> appendLine("  $table — $reason") }
        }
    }
}

/**
 * Satu kolom dalam arsip. [type] adalah `udt_name` Postgres — dipakai hanya untuk memilih
 * cara membaca nilainya, bukan untuk memetakan tipe.
 */
internal data class ExportColumn(val name: String, val type: String) {

    /** Kolom yang isinya rahasia; nilainya diganti penanda, kolomnya tetap ada agar bentuk tabel utuh. */
    val secret: Boolean = SECRET_HINTS.any { it in name.lowercase() }

    /**
     * Geometri PostGIS yang dibaca apa adanya keluar sebagai heksadesimal EWKB — benar,
     * tapi tak terbaca manusia maupun kebanyakan perkakas. EWKT membawa SRID sekaligus
     * koordinat dalam satu teks yang bisa dibaca siapa pun.
     */
    fun selectExpression(): String =
        if (type == GEOMETRY_TYPE) """ST_AsEWKT("$name") AS "$name"""" else """"$name""""

    /** NULL tetap sel kosong (bukan "[disunting]") — ketiadaan nilai bukan rahasia. */
    fun render(value: String?): String = when {
        value == null -> ""
        secret -> REDACTED
        else -> value
    }

    private companion object {
        const val GEOMETRY_TYPE = "geometry"

        /**
         * Pencocokan berdasar nama kolom, bukan daftar kolom yang disebut satu per satu:
         * daftar seperti itu akan ketinggalan pada kolom rahasia BERIKUTNYA yang ditambahkan,
         * dan kegagalannya senyap — rahasia terlanjur ikut terekspor. Kalau kolom tak berbahaya
         * ikut tersunting karena namanya kebetulan cocok, kerugiannya sekadar satu kolom kosong.
         */
        val SECRET_HINTS = listOf(
            "password", "secret", "token", "hash", "api_key", "credential", "private_key", "otp",
        )
    }
}

/** Penanda nilai yang disunting; sengaja teks yang jelas, bukan sel kosong yang menyesatkan. */
internal const val REDACTED = "[disunting]"

/** Escaping RFC-4180: bungkus kutip bila memuat pemisah/kutip/baris baru; kutip digandakan. */
internal fun csvEscape(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
