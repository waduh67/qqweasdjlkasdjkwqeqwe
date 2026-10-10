import { api } from './client'
import { captureCommandSession, command, parameters, type WarehouseCommand } from './warehouse/transport'
import { array, boolean, integer, plainText, record, text, uuid } from './warehouse/codec'

export interface B2BSetting { name: string; address: string; contact: string; technicianId: string; technicianName: string; target: number; active: boolean }
export interface B2BMonth { clientId: string; month: string; setting: B2BSetting; counted: number; remaining: number; weeks: { start: string; end: string; counted: number; missed: boolean }[] }
export interface B2BClient { id: string; revision: number; createdMonth: string; current: B2BMonth; next: B2BSetting }
export interface B2BVisit { id: string; clientId: string; clientName: string; visitDate: string; counted: boolean; notes: string; reporterName: string; createdAt: string; photos: { id: string; contentType: string; sizeBytes: number }[] }
export interface B2BPage<T> { content: T[]; page: number; size: number; totalElements: number }
export interface B2BInput { name: string; address: string; contact: string; technicianId: string; target: number; active: boolean; expectedRevision: number }
export interface B2BTechnician { id: string; name: string }
const base = '/api/v1/b2b'
function setting(value: unknown): B2BSetting {
  const r = record(value)
  const target = integer(r.target)
  if (target < 1 || target > 31) throw new Error('Target B2B tidak valid.')
  return { name: text(r.name), address: text(r.address), contact: text(r.contact), technicianId: uuid(r.technicianId), technicianName: text(r.technicianName), target, active: boolean(r.active) }
}
function month(value: unknown): B2BMonth {
  const r = record(value)
  return { clientId: uuid(r.clientId), month: text(r.month), setting: setting(r.setting), counted: integer(r.counted), remaining: integer(r.remaining), weeks: array(r.weeks, v => {
    const w = record(v); return { start: text(w.start), end: text(w.end), counted: integer(w.counted), missed: boolean(w.missed) }
  }) }
}
function client(value: unknown): B2BClient {
  const r = record(value)
  return { id: uuid(r.id), revision: integer(r.revision), createdMonth: text(r.createdMonth), current: month(r.current), next: setting(r.next) }
}
function visit(value: unknown): B2BVisit {
  const r = record(value)
  return { id: uuid(r.id), clientId: uuid(r.clientId), clientName: text(r.clientName), visitDate: text(r.visitDate), counted: boolean(r.counted), notes: plainText(r.notes), reporterName: text(r.reporterName), createdAt: text(r.createdAt), photos: array(r.photos, v => {
    const p = record(v); return { id: uuid(p.id), contentType: text(p.contentType), sizeBytes: integer(p.sizeBytes) }
  }, 'photos', 5) }
}
async function page<T>(path: string, decode: (value: unknown) => T): Promise<B2BPage<T>> {
  const r = record(await api.get<unknown>(path))
  const result = { content: array(r.content, decode, 'content', 200), page: integer(r.page), size: integer(r.size), totalElements: integer(r.totalElements) }
  if (result.size < 1 || result.size > 200 || result.content.length > result.size || result.content.length > result.totalElements || (result.content.length > 0 && result.page * result.size + result.content.length > result.totalElements)) throw new Error('Halaman B2B tidak valid.')
  return result
}
export const b2b = {
  clients: (q: string, n: number) => page(base + '/clients' + parameters({ q, page: n, size: 20 }), client),
  technicians: (q: string, n: number) => page(base + '/technicians' + parameters({ q, page: n, size: 20 }), v => { const r = record(v); return { id: uuid(r.id), name: text(r.name) } }),
  reports: (m: string, q: string, n: number) => page(base + '/reports' + parameters({ month: m, q, page: n, size: 20 }), month),
  visits: (m: string, clientId: string | undefined, n: number) => page(base + '/visits' + parameters({ month: m, clientId, page: n, size: 20 }), visit),
  save(id: string | undefined, input: B2BInput): WarehouseCommand<B2BClient> {
    const captured = command(base + '/clients' + (id ? '/' + id : ''), id ? 'PUT' : 'POST', input, client)
    const assertSession = captureCommandSession()
    return { ...captured, execute() { assertSession(); return captured.execute() } }
  },
  delete: (c: B2BClient) => api.del<void>(base + '/clients/' + c.id + parameters({ expectedRevision: c.revision })),
  report(id: string, notes: string, photos: File[]): WarehouseCommand<B2BVisit> {
    const key = crypto.randomUUID(), path = base + '/clients/' + id + '/visits'
    const files = photos.map(f => ({ name: f.name, bytes: f.slice(0, f.size, f.type) }))
    const body = JSON.stringify({ id, notes, files: files.map(f => ({ name: f.name, size: f.bytes.size, type: f.bytes.type })) })
    const assertSession = captureCommandSession()
    let pending: Promise<B2BVisit> | null = null
    return { key, path, body, execute() {
      assertSession()
      if (pending) return pending
      const data = new FormData(); data.append('notes', notes)
      files.forEach(f => data.append('photos', f.bytes, f.name))
      pending = api.request<unknown>(path, { method: 'POST', body: data, headers: { 'Idempotency-Key': key } }).then(visit).finally(() => { pending = null })
      return pending
    } }
  },
  photo: (visitId: string, photoId: string) => api.blob(base + '/visits/' + visitId + '/photos/' + photoId),
}
