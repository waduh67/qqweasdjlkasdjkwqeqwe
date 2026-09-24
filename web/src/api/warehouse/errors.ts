import { ApiError } from '../client'
import { WarehouseDataError } from './codec'

const messages: Record<string, string> = {
  MALFORMED_REQUEST: 'Periksa kembali isian, satuan dan format jumlah sebelum menyimpan.',
  INSUFFICIENT_STOCK: 'Stok yang tersedia tidak cukup. Muat ulang stok lalu pilih jumlah atau sumber lain.',
  SOURCE_NOT_VERIFIED: 'Sumber belum memenuhi syarat atau kode/serial sudah digunakan. Periksa barang, status dan referensinya.',
  WRONG_CUSTODIAN: 'Barang bukan berada pada pemegang yang sesuai untuk tindakan ini.',
  ACTIVE_ASSIGNMENT_EXISTS: 'Perangkat masih memiliki penempatan aktif. Selesaikan penempatan tersebut terlebih dahulu.',
  APPROVAL_REQUIRED: 'Tindakan ini memerlukan persetujuan pemeriksa independen.',
  INDEPENDENT_APPROVER_REQUIRED: 'Belum ada pemeriksa independen yang memenuhi izin dan cakupan gudang.',
  IDEMPOTENCY_CONFLICT: 'Referensi transaksi sudah digunakan dengan isi berbeda. Periksa dokumen sebelum membuat transaksi baru.',
  CUTOVER_REQUIRED: 'Gudang belum diaktifkan. Selesaikan pemeriksaan data dan aktivasi gudang.',
  COST_BASIS_REQUIRED: 'Biaya sumber belum tersedia untuk pemeriksaan ini.',
  CURRENCY_MISMATCH: 'Mata uang sumber tidak sesuai dengan kebijakan persetujuan.',
  MATERIAL_OBLIGATION_OUTSTANDING: 'Masih ada material yang perlu dipertanggungjawabkan.',
  MATERIAL_UNPICK_REQUIRED: 'Batalkan penyiapan barang terkait sebelum mengubah kebutuhan.',
  USE_WORKORDER_ASSET_WORKFLOW: 'Gunakan alur perangkat pada work order yang ditugaskan.',
}

export function warehouseError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return 'Izin untuk tindakan ini tidak tersedia. Hubungi pengelola akses.'
    if (error.status === 404) return 'Data tidak ditemukan dalam cakupan gudang Anda.'
    if (error.code?.startsWith('STALE_') || error.code === 'COUNT_STALE') return 'Data atau akses sudah berubah. Muat ulang dokumen sebelum membuat perubahan baru.'
    if (error.message && !/^[A-Z][A-Z0-9_]+$/.test(error.message)) return error.message
    return messages[error.code ?? ''] ?? 'Tindakan belum dapat diselesaikan. Muat ulang data dan periksa isian.'
  }
  if (error instanceof WarehouseDataError) return error.message
  return 'Koneksi terputus. Periksa jaringan lalu coba lagi.'
}
