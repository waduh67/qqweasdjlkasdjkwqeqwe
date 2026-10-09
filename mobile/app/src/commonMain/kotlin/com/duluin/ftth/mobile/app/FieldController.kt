package com.duluin.ftth.mobile.app

import com.duluin.ftth.mobile.data.ReferenceTechnicianRepository
import com.duluin.ftth.mobile.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FieldDestination(val label: String) { HOME("Ringkasan"), WORK("Pekerjaan"), STOCK("Material"), REQUEST("Permintaan"), RETURN("Retur") }
data class FieldScreenState(
    val destination: FieldDestination = FieldDestination.HOME, val busy: Boolean = false, val error: String? = null,
    val notice: String? = null, val epoch: Long? = null, val snapshotCurrent: Boolean = false, val work: MaterialPage<FieldWork>? = null,
    val stock: MaterialPage<FieldStock>? = null, val requests: MaterialPage<FieldRequest>? = null,
    val returns: MaterialPage<FieldReturn>? = null, val detail: FieldWorkDetail? = null,
    val photos: List<FieldPhoto> = emptyList(), val pending: List<FieldPending> = emptyList(),
)

class FieldController(val repository: ReferenceTechnicianRepository, private val scope: CoroutineScope) {
    private val current = MutableStateFlow(FieldScreenState())
    private var operation: Job? = null
    private var generation = 0L
    val state = current.asStateFlow()
    fun action(block: suspend () -> Unit) {
        if (current.value.busy) return
        current.value = current.value.copy(busy = true, error = null, notice = null)
        val started = generation
        operation = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (started == generation) current.value = current.value.copy(error = failure.message ?: "Koneksi belum tersedia. Coba lagi.") }
            finally { if (started == generation) current.value = current.value.copy(busy = false) }
        }
    }
    fun clear() { generation++; operation?.cancel(); current.value = FieldScreenState() }
    fun navigate(destination: FieldDestination) {
        if (current.value.busy) return
        current.value = current.value.copy(destination = destination, detail = null, photos = emptyList())
        load()
    }
    fun load(page: Int = 0, search: String = "") = action { refreshPage(page, search) }
    private suspend fun refreshPage(page: Int = 0, search: String = "") {
        val session = repository.auth.capture()
        current.value = current.value.copy(pending = repository.pending(), snapshotCurrent = false)
        val epoch = repository.workflow()
        val destination = current.value.destination
        when (destination) {
            FieldDestination.HOME -> {
                val work = repository.work(); val stock = repository.materials()
                repository.auth.check(session)
                current.value = current.value.copy(work = work, stock = stock)
            }
            FieldDestination.WORK -> { val work = repository.work(page, search); repository.auth.check(session); current.value = current.value.copy(work = work) }
            FieldDestination.STOCK -> { val stock = repository.materials(page, search); repository.auth.check(session); current.value = current.value.copy(stock = stock) }
            FieldDestination.REQUEST -> { val requests = repository.requests(page, search); repository.auth.check(session); current.value = current.value.copy(requests = requests) }
            FieldDestination.RETURN -> { val returns = repository.returns(page, search); repository.auth.check(session); current.value = current.value.copy(returns = returns) }
        }
        current.value = current.value.copy(epoch = epoch, pending = repository.pending(), snapshotCurrent = true)
    }
    fun open(id: String) = action { refreshDetail(id) }
    suspend fun refreshDetail(id: String) {
        val session = repository.auth.capture()
        val detail = repository.workDetail(id); val photos = repository.photos(id)
        repository.auth.check(session)
        current.value = current.value.copy(detail = detail, photos = photos, pending = repository.pending())
    }
    fun back() { if (!current.value.busy) current.value = current.value.copy(detail = null, photos = emptyList()) }
    suspend fun submit(command: FieldPending) {
        current.value = current.value.copy(pending = repository.pending())
        send(command.key)
    }
    private suspend fun send(key: String) {
        val result = repository.deliver(key)
        when (result) {
            is FieldDelivery.Accepted -> {
                current.value = current.value.copy(notice = "Perintah tersimpan di server.")
                val detailId = current.value.detail?.work?.id
                if (detailId != null) refreshDetail(detailId) else refreshPage()
            }
            is FieldDelivery.Pending -> current.value = current.value.copy(notice = when (result.command.state) {
                SecureDeliveryState.ATTEMPTED -> "Hasil pengiriman belum pasti. Coba ulang dengan perintah yang sama."
                SecureDeliveryState.QUEUED -> "Draf tersimpan terenkripsi. Kirim kembali saat koneksi tersedia."
                SecureDeliveryState.CONFLICT, SecureDeliveryState.REJECTED -> "Perintah memerlukan pemeriksaan ulang."
            })
            is FieldDelivery.Failed -> current.value = current.value.copy(error = result.message)
        }
        current.value = current.value.copy(pending = repository.pending())
    }
    fun retry(key: String) = action { send(key) }
    fun discard(key: String) = action { repository.discard(key); current.value = current.value.copy(pending = repository.pending()) }
    fun photo(evidence: FieldEvidence) = action {
        val work = requireNotNull(current.value.detail).work
        val epoch = requireNotNull(current.value.epoch)
        submit(repository.queuePhoto(epoch, work, evidence))
    }
}
