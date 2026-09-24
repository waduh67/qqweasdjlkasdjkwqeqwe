import { ApiError } from '../client'
import { WarehouseDataError } from './codec'

export function warehouseError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return 'Izin untuk tindakan ini tidak tersedia. Hubungi pengelola akses.'
    if (error.status === 404) return 'Data tidak ditemukan dalam cakupan gudang Anda.'
    if (error.status === 409) return 'Data sudah berubah atau transaksi berbenturan. Muat ulang dokumen sebelum membuat perubahan baru.'
    return error.message
  }
  if (error instanceof WarehouseDataError) return error.message
  return 'Koneksi terputus. Periksa jaringan lalu coba lagi.'
}
