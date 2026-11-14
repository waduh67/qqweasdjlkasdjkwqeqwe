import { array, boolean, integer, nullable, oneOf, plainText, record, text, timestamp, uuid, WarehouseDataError } from './warehouse/codec'

export interface NetworkVendor { readonly id: string; readonly name: string; readonly contact: string; readonly notes: string; readonly active: boolean; readonly revision: number }
export interface VendorInput { readonly name: string; readonly contact: string; readonly notes: string; readonly active: boolean; readonly expectedRevision: number }
export interface IpProbe { readonly status: 'UP' | 'DOWN' | 'ERROR'; readonly checkedAt: string; readonly rttMillis: number | null; readonly error: string | null }
export interface PublicIp { readonly id: string; readonly address: string; readonly subnet: string; readonly gateway: string; readonly vendorId: string | null; readonly vendorName: string | null; readonly notes: string; readonly revision: number; readonly clientId: string | null; readonly clientName: string | null; readonly usedBy?: string; readonly blockId?: string | null; readonly addressRole?: 'HOST' | 'NETWORK' | 'BROADCAST'; readonly probe?: IpProbe | null }
export interface PublicIpInput { readonly address: string; readonly subnet: string; readonly gateway: string; readonly vendorId: string | null; readonly notes: string; readonly expectedRevision: number; readonly usedBy?: string }
export interface IpBlockInput { readonly cidr: string; readonly vendorId: string | null; readonly gateway: string; readonly notes: string }
export function decodeBlock(value: unknown) {
  const r = record(value)
  return { id: uuid(r.id), cidr: text(r.cidr), vendorId: nullable(r.vendorId, uuid, 'vendorId'), vendorName: nullable(r.vendorName, text, 'vendorName'), gateway: plainText(r.gateway), notes: plainText(r.notes), monitoringEnabled: boolean(r.monitoringEnabled), intervalSeconds: integer(r.intervalSeconds), revision: integer(r.revision), addressCount: integer(r.addressCount), hostCount: integer(r.hostCount) }
}
export type IpBlock = ReturnType<typeof decodeBlock>
export function decodeBlockPreview(value: unknown) {
  const r = record(value)
  return { cidr: text(r.cidr), subnet: text(r.subnet), firstAddress: text(r.firstAddress), lastAddress: text(r.lastAddress), addressCount: integer(r.addressCount), hostCount: integer(r.hostCount), existingCount: integer(r.existingCount) }
}
export function decodeProbeJob(value: unknown) {
  const r = record(value)
  const result = { id: uuid(r.id), blockId: nullable(r.blockId, uuid, 'blockId'), state: oneOf(r.state, ['QUEUED', 'RUNNING', 'COMPLETED']), total: integer(r.total), completed: integer(r.completed), up: integer(r.up), down: integer(r.down), errors: integer(r.errors), createdAt: timestamp(r.createdAt) }
  if (result.completed > result.total || result.up + result.down + result.errors > result.completed) throw new WarehouseDataError('probe counters')
  return result
}
export type IpProbeJob = ReturnType<typeof decodeProbeJob>
export interface NetworkDetails {
  readonly picName: string; readonly phone: string
  readonly mainBandwidthMbps: number | null; readonly mainInfrastructure: 'OWN' | 'PARTNER'; readonly mainLastMile: string; readonly mainVendorId: string | null; readonly mainCustomerId: string
  readonly backupBandwidthMbps: number | null; readonly backupVendorId: string | null; readonly backupCustomerId: string
  readonly subnet: string; readonly gateway: string; readonly dns: readonly string[]
  readonly tunnelDial: string; readonly tunnelUsername: string; readonly tunnelRemoteAddress: string; readonly tunnelLocalAddress: string; readonly notes: string
}
export interface NetworkInput { readonly details: NetworkDetails; readonly publicIpIds: readonly string[]; readonly tunnelPassword: string | null; readonly clearTunnelPassword: boolean }
export interface ClientNetwork { readonly details: NetworkDetails; readonly publicIps: readonly PublicIp[]; readonly tunnelPasswordSet: boolean }
export const emptyNetwork: NetworkDetails = { picName: '', phone: '', mainBandwidthMbps: null, mainInfrastructure: 'OWN', mainLastMile: '', mainVendorId: null, mainCustomerId: '', backupBandwidthMbps: null, backupVendorId: null, backupCustomerId: '', subnet: '', gateway: '', dns: [], tunnelDial: '', tunnelUsername: '', tunnelRemoteAddress: '', tunnelLocalAddress: '', notes: '' }
export function decodeVendor(value: unknown): NetworkVendor {
  const r = record(value)
  return { id: uuid(r.id), name: text(r.name), contact: plainText(r.contact), notes: plainText(r.notes), active: boolean(r.active), revision: integer(r.revision) }
}
export function decodeIp(value: unknown): PublicIp {
  const r = record(value)
  return { id: uuid(r.id), address: text(r.address), subnet: plainText(r.subnet), gateway: plainText(r.gateway), vendorId: nullable(r.vendorId, uuid, 'vendorId'), vendorName: nullable(r.vendorName, text, 'vendorName'), notes: plainText(r.notes), revision: integer(r.revision), clientId: nullable(r.clientId, uuid, 'clientId'), clientName: nullable(r.clientName, text, 'clientName'),
    usedBy: r.usedBy === undefined ? (r.clientName ? text(r.clientName) : '') : plainText(r.usedBy), blockId: r.blockId === undefined ? null : nullable(r.blockId, uuid, 'blockId'), addressRole: r.addressRole === undefined ? 'HOST' : oneOf(r.addressRole, ['HOST', 'NETWORK', 'BROADCAST']), probe: r.probe == null ? null : decodeProbe(r.probe) }
}
function decodeProbe(value: unknown): IpProbe {
  const r = record(value), rtt = r.rttMillis
  if (rtt != null && (typeof rtt !== 'number' || !Number.isFinite(rtt) || rtt < 0)) throw new WarehouseDataError('probe rtt')
  return { status: oneOf(r.status, ['UP', 'DOWN', 'ERROR']), checkedAt: timestamp(r.checkedAt), rttMillis: rtt == null ? null : rtt as number, error: nullable(r.error, text, 'error') }
}
export function decodeClientNetwork(value: unknown): ClientNetwork {
  const r = record(value), d = record(r.details)
  const details: NetworkDetails = { picName: plainText(d.picName), phone: plainText(d.phone), mainBandwidthMbps: nullable(d.mainBandwidthMbps, integer, 'mainBandwidthMbps'), mainInfrastructure: oneOf(d.mainInfrastructure, ['OWN', 'PARTNER']), mainLastMile: plainText(d.mainLastMile), mainVendorId: nullable(d.mainVendorId, uuid, 'mainVendorId'), mainCustomerId: plainText(d.mainCustomerId), backupBandwidthMbps: nullable(d.backupBandwidthMbps, integer, 'backupBandwidthMbps'), backupVendorId: nullable(d.backupVendorId, uuid, 'backupVendorId'), backupCustomerId: plainText(d.backupCustomerId), subnet: plainText(d.subnet), gateway: plainText(d.gateway), dns: array(d.dns, text, 'dns', 8), tunnelDial: plainText(d.tunnelDial), tunnelUsername: plainText(d.tunnelUsername), tunnelRemoteAddress: plainText(d.tunnelRemoteAddress), tunnelLocalAddress: plainText(d.tunnelLocalAddress), notes: plainText(d.notes) }
  return { details, publicIps: array(r.publicIps, decodeIp, 'publicIps', 64), tunnelPasswordSet: boolean(r.tunnelPasswordSet) }
}
