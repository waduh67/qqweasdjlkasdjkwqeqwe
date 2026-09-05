package com.duluin.ftth.customer.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.domain.model.CustomerStatus
import java.util.UUID

interface ManageCustomerUseCase {

    fun search(query: String, status: CustomerStatus?, pageRequest: PageRequest): Page<CustomerView>

    /**
     * Pelanggan yang belum punya titik di peta (impor massal menaruhnya di koordinat
     * penampung 0,0). Dipakai peta untuk memilih siapa yang mau ditaruh di titik klik.
     */
    fun findUnmapped(query: String, limit: Int): List<UnmappedCustomerView>

    fun get(id: UUID): CustomerView

    /**
     * Mendaftarkan pelanggan BESERTA langganannya — keduanya lahir bersama, dalam satu
     * transaksi. Pelanggan tanpa paket bukan keadaan yang berguna bagi siapa pun: dia tak
     * tertagih, tak punya akun jaringan, dan tak muncul di antrean pasang. Karena itu jalur
     * normal (layar pendaftaran, PSB ekspres, impor) selalu membawa [plan] — dan tak ada
     * operasi "tambah langganan" menyusul; yang ada hanya menetapkan paket.
     *
     * [plan] null hanya untuk pencatatan calon pelanggan yang paketnya memang belum
     * diputuskan (survei). Paketnya dipasang belakangan lewat
     * [ManageSubscriptionUseCase.setPlan], pintu yang sama dengan ganti paket.
     */
    fun create(command: SaveCustomerCommand, plan: SaveSubscriptionCommand?): CustomerView

    fun update(id: UUID, command: SaveCustomerCommand): CustomerView

    /** Memindah titik rumah pelanggan di peta; kabel drop-nya ikut menempel ulang. */
    fun relocate(id: UUID, location: Coordinate): CustomerView

    fun changeStatus(id: UUID, status: CustomerStatus): CustomerView

    fun delete(id: UUID)
}

data class SaveCustomerCommand(
    /** Kosong/null = server membuat kode berurut otomatis (`CUST-000001`). */
    val code: String?,
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String,
    val location: Coordinate?,
    val areaId: UUID?,
    /** Nomor identitas (NIK/KTP/paspor); opsional. */
    val idCardNumber: String? = null,
)
