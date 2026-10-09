import { useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { getOwnerCandidates, type TenantOwner } from '@/api/tenant'
import type { PageResponse } from '@/api/types'
import { Button, SelectField, TextField } from '@/components/atoms'

export function TenantOwnerSelector({ tenantId, selected, onSelect, busy }: {
  readonly tenantId: string
  readonly selected: TenantOwner | null
  readonly onSelect: (owner: TenantOwner | null) => void
  readonly busy: boolean
}) {
  const [search, setSearch] = useState({ query: '', page: 0 })
  const [results, setResults] = useState<PageResponse<TenantOwner> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true)
    setError(null)
    void getOwnerCandidates(tenantId, search).then(page => { if (active) setResults(page) })
      .catch(err => { if (active) { setResults(null); setError(err instanceof ApiError ? err.message : 'Gagal memuat calon owner') } })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [tenantId, search, retry])
  const choices = results?.content ?? []
  const selectedVisible = selected && choices.some(user => user.id === selected.id)
  return <div className="stack">
    <TextField label="Cari calon owner" type="search" value={search.query} disabled={busy}
      onChange={(_, data) => setSearch({ query: data.value, page: 0 })} placeholder="Nama atau email pengguna tenant" />
    {error && <div role="alert"><p>{error}</p><Button onClick={() => setRetry(value => value + 1)}>Coba lagi</Button></div>}
    <SelectField label="Calon owner" value={selected?.id ?? ''} disabled={busy || loading}
      onChange={(_, data) => onSelect(choices.find(user => user.id === data.value) ?? null)}>
      <option value="">Pilih pengguna aktif</option>
      {selected && !selectedVisible && <option value={selected.id}>{selected.name} · {selected.email}</option>}
      {choices.map(user => <option key={user.id} value={user.id} disabled={user.status !== 'ACTIVE'}>
        {user.name} · {user.email}{user.status !== 'ACTIVE' ? ' · Tidak aktif' : ''}
      </option>)}
    </SelectField>
    <p className="muted" role="status">{loading ? 'Memuat calon owner…' : results?.totalElements === 0 ? 'Tidak ada pengguna yang cocok di tenant ini.' :
      results ? 'Halaman ' + (search.page + 1) + ' dari ' + Math.max(results.totalPages, 1) + ' · ' + results.totalElements + ' pengguna' : ''}</p>
    <div className="toolbar">
      <Button disabled={busy || loading || search.page === 0} onClick={() => setSearch(value => ({ ...value, page: value.page - 1 }))}>Sebelumnya</Button>
      <Button disabled={busy || loading || !results || search.page + 1 >= results.totalPages} onClick={() => setSearch(value => ({ ...value, page: value.page + 1 }))}>Berikutnya</Button>
    </div>
  </div>
}
