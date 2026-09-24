import { api, ApiError, tokenStore } from '../client'
import type { Decoder } from './codec'

/** A captured command can be retried without changing its original key or serialized payload. */
export interface WarehouseCommand<T> {
  readonly key: string
  readonly body: string
  readonly path: string
  execute(): Promise<T>
}

/** A later stage must never continue under a newly logged-in account. Token refresh retains the session. */
export function captureCommandSession() {
  const version = tokenStore.getSessionVersion()
  return () => { if (tokenStore.getSessionVersion() !== version) throw new ApiError(409, 'Sesi berubah. Muat ulang sebelum membuat transaksi baru.') }
}

export function command<T>(path: string, method: 'POST' | 'PUT', input: unknown, decode: Decoder<T>, key = crypto.randomUUID()): WarehouseCommand<T> {
  if (!path.startsWith('/api/') || path.includes('\\') || !/^[\x21-\x7e]{1,240}$/.test(key)) throw new Error('Transaksi gudang tidak valid.')
  const body = JSON.stringify(input)
  if (body === undefined) throw new Error('Transaksi gudang wajib berisi data.')
  let pending: Promise<T> | null = null
  return Object.freeze({ key, body, path, execute() {
    if (pending) return pending
    pending = api.request<unknown>(path, { method, body, headers: { 'Idempotency-Key': key } })
      .then(value => decode(value)).finally(() => { pending = null })
    return pending
  } })
}

export async function query<T>(path: string, decode: Decoder<T>): Promise<T> {
  return decode(await api.get<unknown>(path))
}

/** Capture immutable bytes, filename, revision and key; rebuild only the multipart envelope. */
export function uploadCommand<T>(path: string, revision: number, file: File, decode: Decoder<T>, key = crypto.randomUUID()): WarehouseCommand<T> {
  if (!path.startsWith('/api/') || path.includes('\\') || !/^[\x21-\x7e]{1,240}$/.test(key) || !Number.isSafeInteger(revision) || revision < 0) throw new Error('Unggahan gudang tidak valid.')
  const bytes = file.slice(0, file.size, file.type), filename = file.name
  const body = JSON.stringify({ expectedRevision: revision, filename, size: bytes.size, contentType: bytes.type })
  let pending: Promise<T> | null = null
  return Object.freeze({ key, body, path, execute() {
    if (pending) return pending
    const data = new FormData()
    data.append('expectedRevision', String(revision)); data.append('file', bytes, filename)
    pending = api.request<unknown>(path, { method: 'POST', body: data, headers: { 'Idempotency-Key': key } })
      .then(value => decode(value)).finally(() => { pending = null })
    return pending
  } })
}

export function parameters(values: Readonly<Record<string, string | number | undefined>>): string {
  const result = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) if (value !== undefined && value !== '') result.set(key, String(value))
  const suffix = result.toString()
  return suffix ? `?${suffix}` : ''
}
