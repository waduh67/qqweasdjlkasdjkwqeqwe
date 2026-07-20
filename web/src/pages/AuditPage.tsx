import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { AuditEntry, PageResponse } from '../api/types'

export function AuditPage() {
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void (async () => {
      try {
        const page = await api.get<PageResponse<AuditEntry>>('/api/audit-logs?size=50')
        setEntries(page.content)
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Gagal memuat jejak audit')
      }
    })()
  }, [])

  return (
    <div className="stack">
      <h2 style={{ margin: 0 }}>Jejak Audit</h2>
      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Waktu</th>
              <th>Aksi</th>
              <th>Pelaku</th>
              <th>Objek</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.id}>
                <td className="muted">{new Date(entry.occurredAt).toLocaleString('id-ID')}</td>
                <td>
                  <span className="badge">{entry.action}</span>
                </td>
                <td className="muted">{entry.actorEmail ?? 'sistem'}</td>
                <td className="muted">{entry.entityType ?? '–'}</td>
                <td className="muted">
                  {Object.keys(entry.detail).length === 0
                    ? '–'
                    : Object.entries(entry.detail)
                        .map(([k, v]) => `${k}=${String(v)}`)
                        .join(', ')}
                </td>
              </tr>
            ))}
            {entries.length === 0 && (
              <tr>
                <td colSpan={5} className="muted">
                  Belum ada aktivitas.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
