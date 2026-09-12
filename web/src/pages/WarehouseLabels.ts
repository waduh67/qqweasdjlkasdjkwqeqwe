/**
 * Istilah Indonesia untuk enum gudang + penerjemah id → nama.
 *
 * Dipusatkan karena enum yang sama muncul di lima panel: kalau tiap panel menerjemahkan
 * sendiri, "SCRAP" akan jadi "Dimusnahkan" di satu layar dan "Rusak" di layar sebelah, dan
 * petugas gudang tidak punya cara tahu bahwa keduanya jenis mutasi yang sama.
 */

import type {
  AdjustmentKind,
  DiscrepancyState,
  InventoryApprovalStatus,
  InventoryApprovalType,
  InventoryItemCategory,
  InventoryItemMasterView,
  InventoryStatus,
  InventoryUnit,
  LocationKind,
  LocationView,
  MovementKind,
  MovementState,
  OwnerKind,
} from '@/api/inventory'
import type { Tone } from '@/components/atoms'
import type { User } from '@/api/types'

export const LOCATION_KIND_LABEL: Record<LocationKind, string> = {
  WAREHOUSE: 'Gudang',
  BIN: 'Rak / bin',
  VEHICLE: 'Kendaraan',
  TECHNICIAN: 'Teknisi',
  CUSTOMER_SITE: 'Lokasi pelanggan',
  QUARANTINE: 'Karantina',
  LOST: 'Hilang',
  DISPOSED: 'Dimusnahkan',
  TRANSIT: 'Dalam perjalanan',
}

export const ITEM_CATEGORY_LABEL: Record<InventoryItemCategory, string> = {
  ONT: 'ONU / ONT',
  DROPCORE: 'Dropcore',
  FEEDER: 'Kabel feeder',
  PATCHCORD: 'Patch cord',
  ADAPTER: 'Adapter',
  CONNECTOR: 'Konektor',
  ACCESSORY: 'Aksesori',
  TOOL: 'Perkakas',
  OTHER: 'Lainnya',
}

export const UNIT_LABEL: Record<InventoryUnit, string> = {
  PCS: 'Pcs',
  METER: 'Meter',
  ROLL: 'Roll',
  SET: 'Set',
}

export const INVENTORY_STATUS_LABEL: Record<InventoryStatus, string> = {
  AVAILABLE: 'Tersedia',
  RESERVED: 'Dipesan',
  ISSUED: 'Dikeluarkan',
  IN_TRANSIT: 'Dalam perjalanan',
  CONSUMED: 'Terpakai',
  RETURNED: 'Diretur',
  QUARANTINE: 'Karantina',
  LOST: 'Hilang',
  DISPOSED: 'Dimusnahkan',
  AWAITING_RECEIPT: 'Menunggu barang datang',
}

export const OWNER_KIND_LABEL: Record<OwnerKind, string> = {
  WAREHOUSE: 'Gudang',
  VEHICLE: 'Kendaraan',
  TECHNICIAN: 'Teknisi',
  CUSTOMER: 'Pelanggan',
  REPAIR: 'Perbaikan',
  TRANSIT: 'Perjalanan',
  LOST: 'Hilang',
  DISPOSED: 'Dimusnahkan',
}

export const MOVEMENT_KIND_LABEL: Record<MovementKind, string> = {
  RESTOCK: 'Permintaan restock',
  RECEIVE: 'Penerimaan barang',
  RESERVE: 'Pemesanan',
  RELEASE: 'Pelepasan pesanan',
  ISSUE: 'Pengeluaran',
  ISSUE_EXCEPTION: 'Pengeluaran di luar prosedur',
  TRANSFER: 'Transfer keluar',
  TRANSFER_RECEIPT: 'Transfer masuk',
  RETURN: 'Retur teknisi',
  REPAIR: 'Perbaikan',
  QUARANTINE: 'Karantina',
  ADJUSTMENT: 'Penyesuaian',
  LOSS: 'Kehilangan',
  SCRAP: 'Pemusnahan',
  WRITE_OFF: 'Hapus buku',
  COUNT_VARIANCE: 'Selisih opname',
  DISPOSAL: 'Pembuangan',
  CONSUME: 'Pemakaian',
  REVERSAL: 'Pembalik mutasi',
}

export const MOVEMENT_STATE_LABEL: Record<MovementState, string> = {
  APPLIED: 'Berlaku',
  PENDING_APPROVAL: 'Menunggu persetujuan',
  FAILED_PERMANENT: 'Gagal permanen',
  REQUIRES_MANUAL_REPAIR: 'Perlu perbaikan manual',
}

export const MOVEMENT_STATE_TONE: Record<MovementState, Tone> = {
  APPLIED: 'good',
  PENDING_APPROVAL: 'warning',
  FAILED_PERMANENT: 'critical',
  REQUIRES_MANUAL_REPAIR: 'serious',
}

export const ADJUSTMENT_KIND_LABEL: Record<AdjustmentKind, string> = {
  CORRECTION: 'Koreksi stok',
  LOSS: 'Kehilangan',
  SCRAP: 'Pemusnahan (rusak)',
  WRITE_OFF: 'Hapus buku',
}

export const APPROVAL_TYPE_LABEL: Record<InventoryApprovalType, string> = {
  RESTOCK: 'Restock',
  ISSUE_EXCEPTION: 'Pengeluaran di luar prosedur',
  ADJUSTMENT: 'Penyesuaian',
  LOSS: 'Kehilangan',
  SCRAP: 'Pemusnahan',
  WRITE_OFF: 'Hapus buku',
  COUNT_VARIANCE: 'Selisih opname',
}

export const APPROVAL_STATUS_LABEL: Record<InventoryApprovalStatus, string> = {
  PENDING: 'Menunggu keputusan',
  APPROVED: 'Disetujui',
  REJECTED: 'Ditolak',
  EXPIRED: 'Kedaluwarsa',
  REWORK_REQUIRED: 'Perlu perbaikan',
}

export const APPROVAL_STATUS_TONE: Record<InventoryApprovalStatus, Tone> = {
  PENDING: 'warning',
  APPROVED: 'good',
  REJECTED: 'critical',
  EXPIRED: 'neutral',
  REWORK_REQUIRED: 'serious',
}

export const DISCREPANCY_STATE_LABEL: Record<DiscrepancyState, string> = {
  OPEN: 'Terbuka',
  PENDING_APPROVAL: 'Menunggu persetujuan',
  RESOLVED: 'Selesai',
  REWORK_REQUIRED: 'Perlu hitung ulang',
}

/** Jenis WO yang punya template material. Cermin `WorkOrderType` di modul workorder. */
export const WORK_ORDER_TYPES: readonly string[] = ['PSB', 'REPAIR', 'MIGRATION', 'DISMANTLE', 'PREVENTIVE']

export interface WarehouseNameBook {
  /** "ONT ZTE F660 (ONT-001)" — nama dulu, kode dalam kurung. */
  item: (itemId: string) => string
  /** Nama item saja, untuk kolom sempit. */
  itemName: (itemId: string) => string
  location: (locationId: string) => string
  /** Nama pengguna, atau UUID apa adanya kalau direktori tak termuat. */
  user: (userId: string) => string
}

/**
 * Penerjemah id → nama untuk PEMILIH FORM dan panel master data — bukan untuk daftar baca.
 *
 * Read model gudang (`/balances`, `/ledger`, `/van-stock`, `/variance-report`, antrean
 * persetujuan) sekarang MEMBAWA SENDIRI nama item, jenis lokasi, dan nama orangnya, jadi tidak
 * satu pun daftar itu boleh memakai buku nama ini lagi. Alasannya bukan kerapian: menggabungkan
 * di klien berarti layarnya menuntut `inventory.item.view` dan `iam.user.view`, dua izin yang
 * petugas gudang biasa TIDAK punya — permintaannya kena 403, direktorinya kosong, dan tabelnya
 * mencetak UUID telanjang persis seperti halaman gudang versi lama.
 *
 * Yang tersisa di sini memang butuh direktori: formulir yang menyusun daftar pilihan penyetuju
 * atau pemegang custody, dan panel master data yang izinnya memang `inventory.item.view`.
 * Keduanya hanya terbuka untuk orang yang sudah punya izin itu, jadi direktorinya pasti termuat.
 *
 * Fallback SENGAJA mengembalikan id/kode apa adanya, bukan string kosong: baris yang itemnya
 * sudah dinonaktifkan tetap harus bisa dilacak orang, dan sel kosong akan terbaca sebagai
 * "tidak ada barang".
 */
export function createNameBook(
  items: readonly InventoryItemMasterView[],
  locations: readonly LocationView[],
  users: readonly User[],
): WarehouseNameBook {
  const itemById = new Map(items.map((item) => [item.id, item] as const))
  const locationById = new Map(locations.map((location) => [location.id, location] as const))
  const userById = new Map(users.map((user) => [user.id, user] as const))
  return {
    item: (itemId) => {
      const item = itemById.get(itemId)
      return item ? `${item.name} (${item.code})` : itemId
    },
    itemName: (itemId) => itemById.get(itemId)?.name ?? itemId,
    location: (locationId) => {
      const location = locationById.get(locationId)
      return location ? `${location.code} · ${LOCATION_KIND_LABEL[location.kind]}` : locationId
    },
    user: (userId) => userById.get(userId)?.name ?? userId,
  }
}
