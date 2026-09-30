import type { ReturnDetails, ReturnSource, WarehouseReturn } from '@/api/warehouse/returns'
import { formatBaseQuantity } from '@/api/warehouse/quantity'
import { locationLabel } from './receiptChoices'

export const returnStateLabels: Record<WarehouseReturn['state'], string> = { DRAFT: 'Draf', DISPATCHED: 'Dikirim', RECEIVED_IN_INSPECTION: 'Menunggu inspeksi', ACCEPTED: 'Diterima layak pakai', REPAIR: 'Dalam servis', SUPPLIER_RETURN: 'Dikembalikan ke pemasok', SCRAP: 'Dihapus', LOST: 'Hilang' }
export const returnOriginLabels = { MATERIAL_RESIDUAL: 'Sisa material teknisi', ASSET_REMOVAL: 'Perangkat hasil pelepasan' }
export function returnItemLabel(item: ReturnDetails['references']['item']) { return `${item.name} · ${item.serial ?? item.lotCode ?? item.code}` }
export function returnSourceLabel(source: ReturnSource) { return `${source.code} · ${returnItemLabel(source.item)} · ${formatBaseQuantity(source.quantityBase, source.baseUnit)} ${source.baseUnit === 'MM' ? 'm' : 'unit'}` }
export function returnLocationLabel(details: ReturnDetails, id: string) { const row = details.references.locations.find(row => row.id === id); return row ? locationLabel(row) : `Lokasi ${id}` }
