import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { Area } from '../api/types'
import { useCan } from '../auth/useCan'

export function AreasPage() {
  const { can } = useCan()
  const [areas, setAreas] = useState<Area[]>([])
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function reload() {
    setAreas(await api.get<Area[]>('/api/areas'))
  }

  useEffect(() => {
    void reload().catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat area'))
  }, [])

  async function run(action: () => Promise<unknown>) {
    setError(null)
    try {
      await action()
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  return (
    <div className="stack">
      <h2 style={{ margin: 0 }}>Area / Wilayah</h2>
      <p className="muted">
        Area adalah dimensi <em>scope</em> pada RBAC: pengguna yang dibatasi ke area tertentu hanya melihat aset dan
        tiket di area itu.
      </p>

      {error && <p className="error">{error}</p>}

      {can('iam.area.create') && (
        <div className="card row" style={{ alignItems: 'flex-end' }}>
          <label style={{ flex: 1, marginBottom: 0 }}>
            <span>Kode</span>
            <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="BKS" />
          </label>
          <label style={{ flex: 2, marginBottom: 0 }}>
            <span>Nama</span>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Bekasi" />
          </label>
          <button
            className="primary"
            disabled={!code || !name}
            onClick={() =>
              void run(async () => {
                await api.post('/api/areas', { code, name, parentId: null })
                setCode('')
                setName('')
              })
            }
          >
            Tambah
          </button>
        </div>
      )}

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Kode</th>
              <th>Nama</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {areas.map((area) => (
              <tr key={area.id}>
                <td>{area.code}</td>
                <td className="muted">{area.name}</td>
                <td>
                  {can('iam.area.delete') && (
                    <button className="danger" onClick={() => void run(() => api.del(`/api/areas/${area.id}`))}>
                      Hapus
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {areas.length === 0 && (
              <tr>
                <td colSpan={3} className="muted">
                  Belum ada area.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
