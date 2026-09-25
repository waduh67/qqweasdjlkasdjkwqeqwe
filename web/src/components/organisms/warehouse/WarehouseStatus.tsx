import { StatusBadge, type Tone } from '@/components/atoms/Badge'

const states: Record<string, [string, Tone]> = {
  ACTIVE: ['Aktif', 'good'], ARCHIVED: ['Diarsipkan', 'neutral'], DRAFT: ['Draf', 'neutral'],
  ASSIGNED: ['Ditugaskan', 'accent'], IN_PROGRESS: ['Dikerjakan', 'accent'], DONE: ['Teknis selesai', 'good'],
  COUNTING: ['Sedang dihitung', 'accent'], RECOUNT_REQUIRED: ['Perlu hitung ulang', 'warning'],
  DISCREPANCY: ['Penanganan selisih', 'warning'], REPAIR: ['Dalam servis', 'warning'],
  AVAILABLE: ['Tersedia', 'good'], RESERVED: ['Dipesan', 'accent'], PICKED: ['Disiapkan', 'accent'],
  ISSUED: ['Diserahkan', 'accent'], IN_TRANSIT: ['Dalam perjalanan', 'warning'], CONSUMED: ['Terpakai', 'neutral'],
  RETURNED: ['Dikembalikan', 'warning'], QUARANTINE: ['Karantina', 'warning'], LOST: ['Hilang', 'critical'],
  DISPOSED: ['Dihapuskan', 'neutral'], CUSTOMER_INSTALLED: ['Terpasang di pelanggan', 'good'],
  PENDING: ['Menunggu persetujuan', 'warning'], APPROVED: ['Disetujui', 'good'], REJECTED: ['Ditolak', 'serious'],
  REWORK_REQUIRED: ['Perlu diperbaiki', 'warning'], EXPIRED: ['Kedaluwarsa', 'serious'], STALE: ['Sumber berubah', 'serious'],
  POSTED: ['Dibukukan', 'good'], PARTIAL: ['Sebagian selesai', 'warning'], COMPLETED: ['Selesai', 'good'],
  SUBMITTED: ['Diajukan', 'accent'], PART_RESERVED: ['Sebagian dicadangkan', 'warning'], PART_ISSUED: ['Sebagian dikirim', 'warning'],
  DISPATCHED: ['Dikirim', 'accent'], UNPICKED: ['Persiapan dibatalkan', 'neutral'], PART_RECEIVED: ['Sebagian diterima', 'warning'], RECEIVED: ['Diterima', 'good'], SETTLING: ['Penyelesaian material', 'warning'], CANCELLED: ['Dibatalkan', 'neutral'],
  RECEIVED_IN_INSPECTION: ['Diterima — dalam pemeriksaan', 'warning'], PUTAWAY: ['Selesai ditempatkan', 'good'], CLOSED: ['Ditutup', 'neutral'], ACCEPTED: ['Lolos pemeriksaan', 'good'], SUPPLIER_RETURN: ['Untuk retur pemasok', 'serious'],
  SERVICEABLE: ['Layak pakai', 'good'], DAMAGED: ['Rusak', 'serious'], SCRAP: ['Tidak dapat dipakai', 'critical'],
  UNKNOWN: ['Belum diketahui', 'warning'], LEGACY_UNRESOLVED: ['Asal belum diverifikasi', 'warning'],
  ISP: ['Milik ISP', 'accent'], CUSTOMER: ['Milik pelanggan', 'neutral'], LOAN: ['Pinjaman', 'accent'], SALE: ['Penjualan', 'neutral'],
}
export function WarehouseStatus({ status }: { status: string }) {
  const [label, tone] = states[status] ?? [status, 'neutral']
  return <StatusBadge status={status} label={label} tone={tone} />
}
