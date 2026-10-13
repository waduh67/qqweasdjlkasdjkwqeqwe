import { useCallback } from 'react'
import { Combobox } from '@/components/molecules/Combobox'
import { Button } from '@/components/atoms'
import type { useMapSites } from '@/map/useMapSites'

export function MapSitePicker({ selection }: { selection: ReturnType<typeof useMapSites> }) {
  const { sites, selected, loading, error, select, retry } = selection
  const options = useCallback(async (term: string) => sites.filter(site => `${site.code} ${site.name}`.toLowerCase().includes(term.toLowerCase())), [sites])
  return <section className="map-site-picker map-control-card" aria-label="Site / POP">
    <div className="map-site-picker-field">
      <span>Site / POP</span>
      <Combobox value={selected?.id ?? ''} initialLabel={selected ? `${selected.code} · ${selected.name}` : ''}
        onChange={(_, site) => select(site)} fetchOptions={options} toId={site => site.id} toLabel={site => `${site.code} · ${site.name}`}
        disabled={loading || !!error} debounceMs={0} ariaLabel="Pilih Site / POP"
        placeholder={loading ? 'Memuat site…' : 'Pilih site'} emptyText={sites.length ? 'Tidak ada site yang cocok' : 'Belum ada site'} />
    </div>
    {error && <div className="map-site-picker-error" role="alert"><span>{error}</span><Button variant="subtle" onClick={retry}>Coba lagi</Button></div>}
  </section>
}
