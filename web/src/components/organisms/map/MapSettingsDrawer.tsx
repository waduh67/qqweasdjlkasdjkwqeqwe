import { useEffect } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { Button, Segmented } from '@/components/atoms'
import { BladeHead } from '@/components/molecules'
import { BASEMAPS, BASEMAP_HINTS, BASEMAP_ORDER, MAP_LAYER_GROUPS, type BasemapMode } from '@/map/mapStyle'

/**
 * Toolbar kiri-atas peta: lokasi saya + tarik kabel + tombol taruh perangkat. Tombol
 * tulis (tarik kabel/taruh aset) hanya muncul bila pengguna punya izin terkait; tombol
 * "Lokasi saya" selalu tampil karena geolokasi bukan aksi tulis (semua peran boleh).
 */
/**
 * Pemilih basemap: segmen kecil di dalam kartu info (kiri-bawah), dikumpulkan bersama
 * toggle heatmap & legenda karena sama-sama mengatur "apa yang ditampilkan peta".
 * Sengaja jauh dari alat-edit (kiri-atas) & panel detail (kanan-atas) agar tak
 * bertabrakan. Pakai atom `Segmented` (Fluent) yang legibel di atas kartu kaca bertema.
 */
/**
 * Laci setelan peta (kanan). Alasan keberadaannya bukan "tempat menaruh kontrol",
 * melainkan MENGOSONGKAN peta: pemilih tema, saklar heatmap, dan legenda dulu
 * bertumpuk di kartu mengambang yang menemani operator sepanjang hari padahal
 * disentuh sekali-dua. Di laci, semuanya sejangkauan tapi tak ikut menutupi jaringan.
 *
 * Pilihan tema & legenda diingat di [localStorage] (lihat PREF_*) — preferensi mata
 * satu orang di satu perangkat, bukan data tenant.
 */
export function MapSettingsDrawer({
  basemap,
  onBasemap,
  heatmap,
  onHeatmap,
  canHeatmap,
  showLegend,
  onShowLegend,
  hiddenLayers,
  onToggleLayer,
  onShowAllLayers,
  can,
  onClose,
}: {
  basemap: BasemapMode
  onBasemap: (mode: BasemapMode) => void
  heatmap: boolean
  onHeatmap: (on: boolean) => void
  canHeatmap: boolean
  showLegend: boolean
  onShowLegend: (on: boolean) => void
  hiddenLayers: Set<string>
  onToggleLayer: (key: string, visible: boolean) => void
  onShowAllLayers: () => void
  can: (permission: string) => boolean
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const groups = MAP_LAYER_GROUPS.filter((g) => !g.perm || can(g.perm))
  const anyHidden = groups.some((g) => hiddenLayers.has(g.key))

  return (
    <aside className="map-panel blade map-settings">
      <BladeHead title="Setelan peta" onClose={onClose} />
      <div className="blade-body stack" style={{ gap: '1.1rem' }}>
        <section className="stack" style={{ gap: '0.4rem' }}>
          <h4 className="map-settings-title">Tema peta</h4>
          <BasemapSwitcher value={basemap} onChange={onBasemap} />
          <p className="muted" style={{ margin: 0, fontSize: '0.75rem' }}>
            {BASEMAP_HINTS[basemap]}
          </p>
        </section>

        <section className="stack" style={{ gap: '0.4rem' }}>
          <h4 className="map-settings-title">Tampilan</h4>
          {canHeatmap && (
            <>
              <Checkbox
                label="Heatmap utilisasi ODP"
                checked={heatmap}
                onChange={(_, data) => onHeatmap(!!data.checked)}
              />
              <p className="muted" style={{ margin: 0, fontSize: '0.75rem' }}>
                Mewarnai ODP menurut pemakaian port — untuk melihat di mana kapasitas hampir habis.
              </p>
            </>
          )}
          <Checkbox
            label="Tampilkan legenda"
            checked={showLegend}
            onChange={(_, data) => onShowLegend(!!data.checked)}
          />
        </section>

        {/* Saklar lapisan. Yang tak berizin dilihat tak usah ditawarkan mati-hidupnya —
            operator akan bertanya-tanya kenapa mencentangnya tak memunculkan apa pun. */}
        {groups.length > 0 && (
          <section className="stack" style={{ gap: '0.4rem' }}>
            <div className="spread">
              <h4 className="map-settings-title" style={{ margin: 0 }}>Lapisan</h4>
              {anyHidden && (
                <Button variant="subtle" size="small" onClick={onShowAllLayers}>
                  Tampilkan semua
                </Button>
              )}
            </div>
            {groups.map((group) => (
              <Checkbox
                key={group.key}
                checked={!hiddenLayers.has(group.key)}
                onChange={(_, data) => onToggleLayer(group.key, !!data.checked)}
                label={
                  <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
                    <span
                      aria-hidden="true"
                      style={
                        group.color
                          ? { width: 10, height: 10, borderRadius: '50%', background: group.color, display: 'inline-block' }
                          : // Kabel: contoh berbentuk garis, sebab warnanya berganti
                            // menurut jenis kabelnya (lihat [MAP_LAYER_GROUPS]).
                            { width: 10, height: 2, borderRadius: 999, background: '#7c8aa5', display: 'inline-block' }
                      }
                    />
                    {group.label}
                  </span>
                }
              />
            ))}
            <p className="muted" style={{ margin: 0, fontSize: '0.75rem' }}>
              Lapisan yang dimatikan tak bisa diklik maupun dijadikan ujung kabel — berguna saat
              titik-titik di satu POP saling menutupi.
            </p>
          </section>
        )}

        <section className="stack" style={{ gap: '0.4rem' }}>
          <h4 className="map-settings-title">Petunjuk</h4>
          <p className="muted" style={{ margin: 0, fontSize: '0.78rem', lineHeight: 1.45 }}>
            <strong>Klik kanan</strong> (atau tahan di layar sentuh) pada peta untuk menambah site, OLT, ODF,
            ODC, ODP, joint box, atau menaruh pelanggan yang belum berkoordinat.
            <br />
            <strong>Tarik kabel</strong> dimulai dari panel perangkatnya: klik perangkatnya dulu, lalu tekan
            &quot;Tarik kabel&quot;.
          </p>
        </section>
      </div>
    </aside>
  )
}

function BasemapSwitcher({ value, onChange }: { value: BasemapMode; onChange: (mode: BasemapMode) => void }) {
  return (
    <Segmented
      className="map-basemap"
      ariaLabel="Mode peta"
      value={value}
      onChange={onChange}
      options={BASEMAP_ORDER.map((mode) => ({ value: mode, label: BASEMAPS[mode].label }))}
    />
  )
}
