import { api } from './client'
import { captureCommandSession, command, parameters, query, type WarehouseCommand } from './warehouse/transport'
import { array, integer, nullable, record, WarehouseDataError, type Decoder } from './warehouse/codec'
import { decodeClientNetwork, decodeIp, decodeVendor, type PublicIpInput, type VendorInput } from './networkModels'
import type { B2BPage } from './b2b'

const base = '/api/v1/network-inventory'
function page<T>(decode: Decoder<T>): Decoder<B2BPage<T>> {
  return value => {
    const r = record(value), content = array(r.content, decode, 'content', 200)
    const n = integer(r.page), size = integer(r.size), totalElements = integer(r.totalElements)
    if (size < 1 || size > 200 || content.length > size || content.length > totalElements || (content.length > 0 && n * size + content.length > totalElements)) throw new WarehouseDataError('network page')
    return { content, page: n, size, totalElements }
  }
}
function save<T>(path: string, input: unknown, decode: Decoder<T>): WarehouseCommand<T> {
  const captured = command(path, 'POST', input, decode), session = captureCommandSession()
  return { ...captured, execute() { session(); return captured.execute() } }
}
function update<T>(path: string, input: unknown, decode: Decoder<T>): WarehouseCommand<T> {
  const captured = command(path, 'PUT', input, decode), session = captureCommandSession()
  return { ...captured, execute() { session(); return captured.execute() } }
}
export const networkInventory = {
  vendor: (id: string) => query(base + '/vendors/' + id, decodeVendor),
  vendors: (filter: { readonly q: string; readonly page: number; readonly active?: boolean }) => query(base + '/vendors' + parameters({ q: filter.q, page: filter.page, size: 20, active: filter.active === undefined ? undefined : String(filter.active) }), page(decodeVendor)),
  ips: (filter: { readonly q: string; readonly page: number; readonly status?: 'AVAILABLE' | 'ALLOCATED'; readonly vendorId?: string }) => query(base + '/ips' + parameters({ ...filter, size: 20 }), page(decodeIp)),
  client: (id: string) => query(base + '/clients/' + id, value => nullable(value, decodeClientNetwork, 'network')),
  saveVendor: (id: string | undefined, input: VendorInput) => id ? update(base + '/vendors/' + id, input, decodeVendor) : save(base + '/vendors', input, decodeVendor),
  saveIp: (id: string | undefined, input: PublicIpInput) => id ? update(base + '/ips/' + id, input, decodeIp) : save(base + '/ips', input, decodeIp),
  deleteVendor: (vendor: { readonly id: string; readonly revision: number }) => api.del<void>(base + '/vendors/' + vendor.id + parameters({ expectedRevision: vendor.revision })),
  deleteIp: (ip: { readonly id: string; readonly revision: number }) => api.del<void>(base + '/ips/' + ip.id + parameters({ expectedRevision: ip.revision })),
}
