import type { DraftExpiry } from '@/api/warehouse/draftExpiry'
import { WarehouseTime } from './WarehouseLines'

export function WarehouseDraftExpired({ expiry }: { expiry?: DraftExpiry }) {
  if (!expiry) return null
  return <div className="card stack" role="status">
    <p>Draf kedaluwarsa sejak <WarehouseTime value={expiry.deadline} /> karena batas waktu draf telah lewat.</p>
    <p>Draf ini belum mengubah stok, reservasi, atau kepemilikan. Riwayat tetap tersimpan. Buat draf atau proposal baru dari alur sumber untuk melanjutkan.</p>
  </div>
}
