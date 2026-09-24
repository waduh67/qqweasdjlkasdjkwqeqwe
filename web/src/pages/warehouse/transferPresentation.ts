import type { TransferDetails } from '@/api/warehouse/transfers'
import { locationLabel } from './receiptChoices'

export function transferLineLabel(details: TransferDetails, id: string) {
  const ref = details.references.lines.find(line => line.lineId === id)
  return `${ref?.skuName ?? 'Nama barang tidak tersedia'}${ref?.serial ? ` · ${ref.serial}` : ref?.lotCode ? ` · ${ref.lotCode}` : ''}`
}
export function transferLocationLabel(details: TransferDetails, id: string) {
  const ref = details.references.locations.find(row => row.id === id)
  return ref ? locationLabel(ref) : 'Nama lokasi tidak tersedia'
}
export function transferPersonLabel(details: TransferDetails, id: string) {
  return details.references.people.find(row => row.id === id)?.name ?? 'Nama petugas tidak tersedia'
}
