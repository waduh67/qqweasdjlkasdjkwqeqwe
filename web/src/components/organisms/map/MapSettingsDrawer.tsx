import { useEffect } from 'react'
import { Checkbox, Text, typographyStyles } from '@fluentui/react-components'
import { Button } from '@/components/atoms'
import { BladeHead } from '@/components/molecules'
import { MAP_LAYER_GROUPS } from '@/map/mapStyle'

export function MapSettingsDrawer({
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
          <h4 className="map-settings-title" style={typographyStyles.subtitle2}>Tampilan</h4>
          {canHeatmap && (
            <>
              <Checkbox
                label="Heatmap utilisasi ODP"
                checked={heatmap}
                onChange={(_, data) => onHeatmap(!!data.checked)}
              />
              <Text as="p" className="muted" size={100} block style={{ margin: 0 }}>
                Mewarnai ODP menurut pemakaian port — untuk melihat di mana kapasitas hampir habis.
              </Text>
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
              <h4 className="map-settings-title" style={{ ...typographyStyles.subtitle2, margin: 0 }}>Lapisan</h4>
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
            <Text as="p" className="muted" size={100} block style={{ margin: 0 }}>
              Lapisan yang dimatikan tak bisa diklik maupun dijadikan ujung kabel — berguna saat
              titik-titik di satu POP saling menutupi.
            </Text>
          </section>
        )}

        <section className="stack" style={{ gap: '0.4rem' }}>
          <h4 className="map-settings-title" style={typographyStyles.subtitle2}>Petunjuk</h4>
          <Text as="p" className="muted" size={100} block style={{ ...typographyStyles.caption1, margin: 0 }}>
            <Text as="strong" weight="semibold">Klik kanan</Text> (atau tahan di layar sentuh) pada peta untuk menambah site, OLT, ODF,
            ODC, ODP, joint box, atau menaruh pelanggan yang belum berkoordinat.
            <br />
            <Text as="strong" weight="semibold">Tarik kabel</Text> dimulai dari panel perangkatnya: klik perangkatnya dulu, lalu tekan
            &quot;Tarik kabel&quot;.
          </Text>
        </section>
      </div>
    </aside>
  )
}
