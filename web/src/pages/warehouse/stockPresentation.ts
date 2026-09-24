import type { StockEvent } from '@/api/warehouse/stock'

export const stockLink = (values: Readonly<Record<string, string>>) => `/warehouse/stock?${new URLSearchParams(values)}`
export const custodianLabels: Record<string, string> = { WAREHOUSE: 'Gudang', VEHICLE: 'Kendaraan', TECHNICIAN: 'Teknisi', CUSTOMER: 'Pelanggan', REPAIR: 'Servis', TRANSIT: 'Dalam perjalanan', LOST: 'Hilang', DISPOSED: 'Dihapuskan' }
export const segmentLabels: Record<string, string> = { REEL: 'Reel asal', CUT: 'Potongan', REMNANT: 'Sisa potongan', BULK: 'Curah', SERIAL: 'Unit serial' }
export const segmentStateLabels: Record<string, string> = { ACTIVE: 'Bagian aktif', SPLIT: 'Sudah dipecah', RETIRED: 'Selesai / dihentikan' }
const movementLabels: Record<string, string> = {
  RESTOCK: 'Penambahan stok', RECEIVE: 'Penerimaan', RESERVE: 'Reservasi', RELEASE: 'Pelepasan reservasi', ISSUE: 'Pengeluaran', ISSUE_EXCEPTION: 'Pengecualian pengeluaran',
  TRANSFER: 'Pemindahan', TRANSFER_RECEIPT: 'Penerimaan transfer', RETURN: 'Pengembalian', REPAIR: 'Servis', QUARANTINE: 'Karantina', ADJUSTMENT: 'Penyesuaian', LOSS: 'Kehilangan',
  SCRAP: 'Barang tidak layak', WRITE_OFF: 'Penghapusan', COUNT_VARIANCE: 'Selisih penghitungan', DISPOSAL: 'Pemusnahan', CONSUME: 'Pemakaian', REVERSAL: 'Pembalikan', DEPLOY: 'Pemasangan', TITLE_TRANSFER: 'Alih kepemilikan', TITLE_CORRECTION: 'Koreksi kepemilikan',
}
export function stockEventLabel(event: StockEvent) {
  switch (event.kind) {
    case 'MOVEMENT_LEG': return `${event.direction === 'IN' ? 'Masuk' : 'Keluar'} · ${movementLabels[event.movementKind] ?? event.movementKind}`
    case 'INSPECTION': return 'Keputusan pemeriksaan'
    case 'RESERVATION': return `Reservasi · ${event.eventKind}`
    case 'MATERIAL_FACT': return event.returned ? 'Material dikembalikan' : event.installed ? 'Material dipasang / digunakan' : 'Fakta pemakaian material'
  }
}
