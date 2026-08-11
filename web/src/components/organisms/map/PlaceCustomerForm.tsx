import { useEffect, useState } from 'react'
import { MessageBar, MessageBarBody } from '@fluentui/react-components'
import { api } from '@/api/client'
import type { UnmappedCustomer } from '@/api/network'
import { Button, TextField } from '@/components/atoms'
import { BladeHead } from '@/components/molecules'
import { SEARCH_DEBOUNCE_MS } from '@/map/mapAssets'

/**
 * Pemilih "pelanggan belum berkoordinat" untuk titik yang barusan ditunjuk. Peta tak
 * membuat pelanggan baru — pendaftaran ada di halaman Pelanggan lengkap dengan paket
 * & identitas; yang kurang di peta justru sebaliknya: pelanggan hasil impor massal
 * yang sudah terdaftar tapi tak pernah dapat titik. Jadi ini daftar-pilih, bukan form.
 */
export function PlaceCustomerForm({
  lng,
  lat,
  onCancel,
  onSave,
}: {
  lng: number
  lat: number
  onCancel: () => void
  onSave: (customer: UnmappedCustomer) => void
}) {
  const [query, setQuery] = useState('')
  const [rows, setRows] = useState<UnmappedCustomer[] | null>(null)
  const [picked, setPicked] = useState<UnmappedCustomer | null>(null)
  const [busy, setBusy] = useState(false)

  // Ketikan diendapkan dulu: daftarnya dicari di server (yang belum berkoordinat bisa
  // ribuan sesudah impor), dan menembakkan satu kueri per huruf hanya membuat hasil
  // lama menimpa hasil baru. `alive` menjaga respons basi tak mendarat.
  useEffect(() => {
    let alive = true
    const timer = window.setTimeout(() => {
      api
        .get<UnmappedCustomer[]>(`/api/customers/unmapped?limit=30&query=${encodeURIComponent(query.trim())}`)
        .then((list) => {
          if (alive) setRows(list)
        })
        .catch(() => {
          if (alive) setRows([])
        })
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      alive = false
      window.clearTimeout(timer)
    }
  }, [query])

  const submit = () => {
    if (!picked) return
    setBusy(true)
    onSave(picked)
  }

  return (
    <aside className="map-panel blade">
      <BladeHead
        title="Taruh pelanggan"
        subtitle={`${lat.toFixed(6)}, ${lng.toFixed(6)} · seret pin untuk menggeser`}
        onClose={onCancel}
        closeLabel="Batal"
      />
      <div className="blade-body stack">
        <TextField
          label="Cari pelanggan"
          value={query}
          onChange={(_, data) => setQuery(data.value)}
          placeholder="Kode, nama, alamat, atau nomor HP"
        />

        {rows == null && <p className="muted" style={{ margin: 0 }}>Memuat…</p>}
        {rows != null && rows.length === 0 && (
          <MessageBar intent="info">
            <MessageBarBody>
              {query.trim()
                ? 'Tak ada pelanggan belum berkoordinat yang cocok.'
                : 'Semua pelanggan sudah punya titik di peta. Pelanggan baru didaftarkan di halaman Pelanggan.'}
            </MessageBarBody>
          </MessageBar>
        )}
        {rows != null && rows.length > 0 && (
          <ul className="pick-list" role="listbox" aria-label="Pelanggan belum berkoordinat">
            {rows.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  role="option"
                  aria-selected={picked?.id === c.id}
                  className={`pick-item${picked?.id === c.id ? ' is-picked' : ''}`}
                  onClick={() => setPicked(c)}
                >
                  <span className="pick-title">
                    {c.code} — {c.name}
                  </span>
                  <span className="muted">{[c.address, c.phone].filter(Boolean).join(' · ')}</span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <div className="row">
          <Button variant="primary" disabled={!picked || busy} onClick={submit}>
            {picked ? `Taruh ${picked.code} di sini` : 'Pilih pelanggan dulu'}
          </Button>
          <Button variant="subtle" onClick={onCancel}>
            Batal
          </Button>
        </div>
      </div>
    </aside>
  )
}
