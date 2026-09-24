import { StatusBadge, type Tone } from '@/components/atoms/Badge'

const states: Record<string, [string, Tone]> = {
  ACTIVE: ['Aktif', 'good'], ARCHIVED: ['Diarsipkan', 'neutral'], DRAFT: ['Draf', 'neutral'],
  AVAILABLE: ['Tersedia', 'good'], RESERVED: ['Dipesan', 'accent'], PICKED: ['Disiapkan', 'accent'],
  ISSUED: ['Diserahkan', 'accent'], IN_TRANSIT: ['Dalam perjalanan', 'warning'], CONSUMED: ['Terpakai', 'neutral'],
  RETURNED: ['Dikembalikan', 'warning'], QUARANTINE: ['Karantina', 'warning'], LOST: ['Hilang', 'critical'],
  DISPOSED: ['Dihapuskan', 'neutral'], CUSTOMER_INSTALLED: ['Terpasang di pelanggan', 'good'],
  PENDING: ['Menunggu persetujuan', 'warning'], APPROVED: ['Disetujui', 'good'], REJECTED: ['Ditolak', 'serious'],
  REWORK_REQUIRED: ['Perlu diperbaiki', 'warning'], EXPIRED: ['Kedaluwarsa', 'serious'], STALE: ['Sumber berubah', 'serious'],
  POSTED: ['Dibukukan', 'good'], PARTIAL: ['Sebagian selesai', 'warning'], COMPLETED: ['Selesai', 'good'],
  SERVICEABLE: ['Layak pakai', 'good'], DAMAGED: ['Rusak', 'serious'], SCRAP: ['Tidak dapat dipakai', 'critical'],
  UNKNOWN: ['Belum diketahui', 'warning'], LEGACY_UNRESOLVED: ['Asal belum diverifikasi', 'warning'],
  ISP: ['Milik ISP', 'accent'], CUSTOMER: ['Milik pelanggan', 'neutral'], LOAN: ['Pinjaman', 'accent'], SALE: ['Penjualan', 'neutral'],
}
export function WarehouseStatus({ status }: { status: string }) {
  const [label, tone] = states[status] ?? [status, 'neutral']
  return <StatusBadge status={status} label={label} tone={tone} />
}
