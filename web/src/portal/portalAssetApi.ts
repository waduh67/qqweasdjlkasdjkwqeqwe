import { nullable, oneOf, pageOf, record, text } from '@/api/warehouse/codec'
import { timestamp } from '@/api/warehouse/approvals'
import { portalApiClient } from './portalClient'

/** Independent portal projection: never decode operational assignment, warehouse or evidence references. */
export function portalAsset(value: unknown, path = 'device') {
  const row = record(value, path)
  return { deviceLabel: text(row.deviceLabel, path), serialNumber: text(row.serialNumber, path), ownershipMode: oneOf(row.ownershipMode, ['LOAN', 'SALE'], path),
    legalOwner: oneOf(row.legalOwner, ['ISP', 'CUSTOMER'], path), provenance: oneOf(row.provenance, ['RECEIPT', 'OPENING_BALANCE', 'UNKNOWN'], path),
    installedAt: timestamp(row.installedAt, path), removedAt: nullable(row.removedAt, timestamp, path) }
}
export async function getPortalAssets(page: number) {
  if (!Number.isSafeInteger(page) || page < 0) throw new Error('Halaman perangkat tidak valid.')
  return pageOf(portalAsset)(await portalApiClient.get(`/api/portal/me/assets?page=${page}&size=10`))
}
