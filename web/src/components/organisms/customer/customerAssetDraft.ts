import type { AssetTopology } from '@/api/warehouse/customerAssets'

export interface OdpChoice { id: string; code: string; capacity: number }
export interface AssetTopologyDraft { odp: OdpChoice | null; port: string; rx: string }
export const emptyAssetTopology = (): AssetTopologyDraft => ({ odp: null, port: '', rx: '' })
export function assetTopologyInput(draft: AssetTopologyDraft, required = false): AssetTopology | null {
  if (!draft.odp) { if (required || draft.port.trim() || draft.rx.trim()) throw new Error('Pilih ODP tujuan sebelum mengisi port dan redaman.'); return null }
  const port = Number(draft.port), rx = draft.rx.trim() ? Number(draft.rx) : null
  if (!/^\d+$/.test(draft.port) || !Number.isSafeInteger(port) || port < 1 || port > draft.odp.capacity) throw new Error('Nomor port harus berada dalam kapasitas ODP.')
  if (rx !== null && (!Number.isFinite(rx) || rx < -40 || rx > 0)) throw new Error('Redaman harus antara -40 dan 0 dBm.')
  return { odpId: draft.odp.id, portNumber: port, installRxPowerDbm: rx }
}
