import type { StockEvent } from '@/api/warehouse/stock'

export const stockLink = (values: Readonly<Record<string, string>>) => `/warehouse/stock?${new URLSearchParams(values)}`
export const custodianLabels: Record<string, string> = { WAREHOUSE: 'Gudang', VEHICLE: 'Kendaraan', TECHNICIAN: 'Teknisi', CUSTOMER: 'Pelanggan', REPAIR: 'Servis', TRANSIT: 'Dalam perjalanan', LOST: 'Hilang', DISPOSED: 'Dihapuskan' }
export const segmentLabels: Record<string, string> = { REEL: 'Reel asal', CUT: 'Potongan', REMNANT: 'Sisa potongan', BULK: 'Curah', SERIAL: 'Unit serial' }
export const segmentStateLabels: Record<string, string> = { ACTIVE: 'Bagian aktif', SPLIT: 'Sudah dipecah', RETIRED: 'Selesai / dihentikan' }
export function stockEventLabel(event: StockEvent) {
  switch (event.kind) {
    case 'MOVEMENT_LEG': return `${event.direction === 'IN' ? 'Masuk' : 'Keluar'} · ${event.movementKind}`
    case 'INSPECTION': return 'Keputusan pemeriksaan'
    case 'RESERVATION': return `Reservasi · ${event.eventKind}`
    case 'MATERIAL_FACT': return event.returned ? 'Material dikembalikan' : event.installed ? 'Material dipasang / digunakan' : 'Fakta pemakaian material'
  }
}
