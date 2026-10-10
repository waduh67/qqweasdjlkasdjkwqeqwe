package com.duluin.ftth.fieldservice.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.*
import com.duluin.ftth.common.security.ReadOnlyLockGuard
import com.duluin.ftth.common.storage.DeleteGuard
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.fieldservice.adapter.outbound.persistence.B2BSql
import com.duluin.ftth.fieldservice.adapter.outbound.persistence.B2BStore
import com.duluin.ftth.fieldservice.adapter.outbound.persistence.uuid
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.NeTechnicianApi
import com.duluin.ftth.iam.UserRef
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.imageio.ImageIO

@Service
@Transactional
class B2BService(private val store: B2BStore, private val authority: CurrentAuthorityApi,
    private val iam: IamApi, private val engineers: NeTechnicianApi, private val clock: B2BOperationalClock,
    private val storage: ObjectStorage, private val readOnly: ObjectProvider<ReadOnlyLockGuard>) {
    private val mapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    fun technicians(query: String, page: PageRequest): Page<UserRef> {
        admin(access(), false)
        return engineers.searchNetworkEngineers(query.take(200),page)
    }
    fun clients(query: String, page: PageRequest): Page<B2BClientView> {
        admin(access(),false)
        val today=clock.today()
        return store.use { sql ->
            sql.lock(); sql.prepare(today.withDayOfMonth(1))
            val ids=sql.page("""SELECT c.id FROM b2b_client c JOIN b2b_client_month m ON m.tenant_id=c.tenant_id AND m.client_id=c.id
                WHERE c.tenant_id=? AND m.month=? AND lower(m.name) LIKE ? ORDER BY lower(m.name),c.id""",
                page,sql.tenant,today.withDayOfMonth(1),"%${query.trim().lowercase().take(200)}%") { it.uuid("id") }
            ids.map { sql.client(it,today) }
        }
    }
    fun save(id: UUID?, input: B2BClientInput, key: UUID): B2BClientView {
        val actor=access(); admin(actor,true)
        val normalized=validate(input)
        val canonical=mapper.writeValueAsString(mapOf("id" to id,"input" to normalized))
        val today=clock.today(); val month=today.withDayOfMonth(1)
        return store.use { sql ->
            sql.lock()
            sql.query("SELECT actor_id,canonical,result::text FROM b2b_client_command WHERE tenant_id=? AND operation_key=?",sql.tenant,key) {
                Triple(it.uuid("actor_id"),it.getString("canonical"),it.getString("result"))
            }.singleOrNull()?.let {
                if(it.first!=actor.fence.identity.userId || it.second!=canonical) throw ConflictException("Kunci pengiriman sudah dipakai untuk perubahan lain")
                return@use mapper.readValue(it.third,B2BClientView::class.java)
            }
            val technician=iam.findUser(normalized.technicianId)
            if(technician?.active!=true || !technician.pureNetworkEngineer) throw ValidationException("Pilih Teknisi NE aktif tanpa role lain")
            sql.prepare(month)
            val target=id ?: UUID.randomUUID()
            if(id==null) {
                if(input.expectedRevision!=0L) throw ValidationException("Revisi client baru harus nol")
                sql.update("INSERT INTO b2b_client(id,tenant_id,created_month) VALUES (?,?,?)",target,sql.tenant,month)
                sql.saveSetting(target,month,normalized)
                sql.prepare(month)
            } else {
                val current=sql.client(target,today)
                if(current.revision!=input.expectedRevision) throw ConflictException("Client berubah. Muat ulang sebelum menyimpan")
                sql.saveSetting(target,month.plusMonths(1),normalized)
                sql.update("UPDATE b2b_client SET revision=revision+1 WHERE tenant_id=? AND id=?",sql.tenant,target)
            }
            val result=sql.client(target,today)
            sql.update("INSERT INTO b2b_client_command(tenant_id,operation_key,actor_id,canonical,result) VALUES (?,?,?,?,?::jsonb)",
                sql.tenant,key,actor.fence.identity.userId,canonical,mapper.writeValueAsString(result))
            result
        }
    }
    fun delete(id: UUID, revision: Long) {
        admin(access(),true)
        store.use { sql ->
            sql.lock()
            val current=sql.query("SELECT revision FROM b2b_client WHERE tenant_id=? AND id=?",sql.tenant,id) { it.getLong(1) }.singleOrNull()
                ?: throw NotFoundException("Client B2B tidak ditemukan")
            if(current!=revision) throw ConflictException("Client berubah. Muat ulang sebelum menghapus")
            if(sql.query("SELECT id FROM b2b_visit WHERE tenant_id=? AND client_id=? LIMIT 1",sql.tenant,id) { true }.isNotEmpty())
                throw ConflictException("Client memiliki riwayat visit. Nonaktifkan untuk bulan depan")
            sql.update("DELETE FROM b2b_client WHERE tenant_id=? AND id=?",sql.tenant,id)
        }
    }
    fun reports(month: YearMonth, query: String, page: PageRequest): Page<B2BMonth> {
        val actor=access(); val all=readScope(actor); val today=clock.today(); val date=month.atDay(1)
        if(date>today.withDayOfMonth(1)) throw ValidationException("Rekap bulan depan belum tersedia")
        return store.use { sql ->
            sql.lock(); sql.prepare(date)
            val ids=sql.page("""SELECT client_id FROM b2b_client_month WHERE tenant_id=? AND month=? AND lower(name) LIKE ?
                AND (? OR technician_id=?) ORDER BY lower(name),client_id""",page,sql.tenant,date,
                "%${query.trim().lowercase().take(200)}%",all,actor.fence.identity.userId) { it.uuid("client_id") }
            ids.map { sql.month(it,date,today) }
        }
    }
    fun visits(month: YearMonth, clientId: UUID?, page: PageRequest): Page<B2BVisitView> {
        val actor=access(); val all=readScope(actor)
        if(month>YearMonth.from(clock.today())) throw ValidationException("Riwayat bulan depan belum tersedia")
        return store.use { sql ->
            val ids=sql.page("""SELECT id FROM b2b_visit WHERE tenant_id=? AND month=? AND (? OR reporter_id=?)
                AND (?::uuid IS NULL OR client_id=?::uuid) ORDER BY created_at DESC,id""",page,sql.tenant,month.atDay(1),all,
                actor.fence.identity.userId,clientId,clientId) { it.uuid("id") }
            ids.map(sql::visit)
        }
    }
    fun report(id: UUID, notes: String, photos: List<B2BPhotoInput>, key: UUID): B2BVisitView {
        val actor=access(); ne(actor,true)
        val text=notes.trim()
        if(text.length !in 1..5000) throw ValidationException("Catatan wajib diisi, maksimal 5000 karakter")
        if(photos.size !in 1..5) throw ValidationException("Lampirkan 1 sampai 5 foto JPEG atau PNG")
        photos.forEach(::validatePhoto)
        val hash=sha256(mapper.writeValueAsBytes(mapOf("client" to id,"notes" to text,
            "photos" to photos.map { mapOf("type" to it.contentType,"hash" to sha256(it.bytes)) })))
        val today=clock.today(); val month=today.withDayOfMonth(1)
        return store.use { sql ->
            sql.lock()
            sql.query("SELECT id,reporter_id,payload_hash FROM b2b_visit WHERE tenant_id=? AND operation_key=?",sql.tenant,key) {
                Triple(it.uuid("id"),it.uuid("reporter_id"),it.getString("payload_hash"))
            }.singleOrNull()?.let {
                if(it.second!=actor.fence.identity.userId || it.third!=hash) throw ConflictException("Kunci visit sudah dipakai untuk laporan lain")
                return@use sql.visit(it.first)
            }
            if(!sql.exists(id)) throw NotFoundException("Client B2B tidak ditemukan")
            sql.prepare(month)
            val assignment=sql.month(id,month,today).setting
            if(assignment.technicianId!=actor.fence.identity.userId) throw NotFoundException("Client tidak ditugaskan kepada Anda pada bulan ini")
            if(!assignment.active) throw ValidationException("Client tidak aktif pada bulan ini")
            val reporter=requireNotNull(iam.findUser(actor.fence.identity.userId))
            val visit=UUID.randomUUID()
            val counted=sql.query("SELECT id FROM b2b_visit WHERE tenant_id=? AND client_id=? AND visit_date=? AND counted",sql.tenant,id,today) { true }.isEmpty()
            sql.update("""INSERT INTO b2b_visit(id,tenant_id,client_id,month,visit_date,counted,notes,reporter_id,reporter_name,created_at,operation_key,payload_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",visit,sql.tenant,id,month,today,counted,text,reporter.id,reporter.name,Timestamp.from(clock.instant()),key,hash)
            photos.forEach { input ->
                val photo=UUID.randomUUID(); val objectKey="${sql.tenant}/b2b/$visit/$photo"; val photoHash=sha256(input.bytes)
                rollbackCleanup(sql.tenant,objectKey,photoHash)
                storage.put(objectKey,input.contentType,input.bytes)
                val stored=storage.get(objectKey)
                if(stored.contentType!=input.contentType || sha256(stored.bytes)!=photoHash) throw ConflictException("Bukti foto gagal diverifikasi")
                sql.update("INSERT INTO b2b_visit_photo(id,tenant_id,visit_id,object_key,content_type,size_bytes,sha256) VALUES (?,?,?,?,?,?,?)",
                    photo,sql.tenant,visit,objectKey,input.contentType,input.bytes.size.toLong(),photoHash)
            }
            sql.visit(visit)
        }
    }
    fun photo(visit: UUID, id: UUID): B2BPhotoInput {
        val actor=access(); val all=readScope(actor)
        return store.use { sql ->
            val reporter=sql.query("SELECT reporter_id FROM b2b_visit WHERE tenant_id=? AND id=?",sql.tenant,visit) { it.uuid("reporter_id") }.singleOrNull()
            if(reporter==null || (!all && reporter!=actor.fence.identity.userId)) throw NotFoundException("Bukti visit tidak ditemukan")
            val photo=sql.photos(visit).singleOrNull { it.id==id } ?: throw NotFoundException("Bukti visit tidak ditemukan")
            val value=storage.get(photo.key)
            if(value.contentType!=photo.contentType || value.size!=photo.size || sha256(value.bytes)!=photo.hash)
                throw ConflictException("Bukti foto berubah di penyimpanan")
            B2BPhotoInput(photo.contentType,value.bytes)
        }
    }
    private fun access(): CurrentAuthority = authority.lockCurrent().also { it.fence.assertHeld() }
    private fun admin(actor: CurrentAuthority, write: Boolean) {
        requirePermission(actor,if(write) "b2b.client.manage" else "b2b.client.view",write)
    }
    private fun ne(actor: CurrentAuthority, write: Boolean) {
        requirePermission(actor,if(write) "b2b.visit.report" else "b2b.visit.view",write)
        val user=iam.findUser(actor.fence.identity.userId)
        if(user?.active!=true || !user.pureNetworkEngineer) throw AccessDeniedException("Visit B2B hanya untuk Teknisi NE tanpa role lain")
    }
    private fun readScope(actor: CurrentAuthority): Boolean {
        if(actor.platformAdmin || "b2b.client.view" in actor.permissions) { admin(actor,false); return true }
        ne(actor,false); return false
    }
    private fun requirePermission(actor: CurrentAuthority, permission: String, write: Boolean) {
        if(!actor.platformAdmin && permission !in actor.permissions) throw AccessDeniedException("Tidak memiliki izin B2B")
        if(write && readOnly.getIfAvailable()?.isReadOnly()==true) throw SubscriptionLockedException()
    }
    private fun validate(input: B2BClientInput): B2BClientInput {
        val value=input.copy(name=input.name.trim(),address=input.address.trim(),contact=input.contact.trim())
        if(value.name.length !in 2..200 || value.address.length !in 1..2000 || value.contact.length !in 1..300)
            throw ValidationException("Isi nama (2–200 karakter), alamat dan kontak client")
        if(value.target !in 1..31 || value.expectedRevision<0) throw ValidationException("Target harus 1 sampai 31 visit per bulan")
        return value
    }
    private fun validatePhoto(photo: B2BPhotoInput) {
        if(photo.bytes.size !in 1..5*1024*1024 || photo.contentType !in setOf("image/jpeg","image/png"))
            throw ValidationException("Foto harus JPEG atau PNG, maksimal 5 MB per foto")
        val expected=if(photo.contentType=="image/png") byteArrayOf(-119,80,78,71,13,10,26,10) else byteArrayOf(-1,-40,-1)
        if(!photo.bytes.take(expected.size).toByteArray().contentEquals(expected)) throw ValidationException("Isi foto tidak sesuai format")
        ImageIO.createImageInputStream(ByteArrayInputStream(photo.bytes)).use { stream ->
            val readers=ImageIO.getImageReaders(stream)
            if(!readers.hasNext()) throw ValidationException("Foto tidak dapat dibaca")
            val reader=readers.next()
            try {
                reader.input=stream
                val width=reader.getWidth(0); val height=reader.getHeight(0)
                if(width<=0 || height<=0 || width.toLong()*height>25_000_000) throw ValidationException("Ukuran gambar terlalu besar")
                reader.read(0) ?: throw ValidationException("Foto tidak dapat dibaca")
            } catch (failure: java.io.IOException) { throw ValidationException("Foto rusak atau tidak dapat dibaca") }
            finally { reader.dispose() }
        }
    }
    private fun rollbackCleanup(tenant: UUID, key: String, hash: String) {
        TransactionSynchronizationManager.registerSynchronization(object: TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if(status!=TransactionSynchronization.STATUS_ROLLED_BACK) return
                try {
                    val value=storage.get(key)
                    if(sha256(value.bytes)!=hash) return
                    val meta=storage.head(tenant.toString(),key)
                    if(!storage.deleteIfMatch(tenant.toString(),key,DeleteGuard(meta.etag,meta.version)))
                        logger.warn("B2B rollback photo cleanup pending: {}",key)
                } catch(failure: Exception) { logger.warn("B2B rollback photo cleanup failed: {}",key,failure) }
            }
        })
    }
    private fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
