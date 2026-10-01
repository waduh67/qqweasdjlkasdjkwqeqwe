import { Segmented } from '@/components/atoms'
import { BASEMAPS, BASEMAP_HINTS, BASEMAP_ORDER, type BasemapMode } from '@/map/basemaps'

export function BasemapPicker({ basemap, onBasemap, basemapFailed }: {
  readonly basemap: BasemapMode
  readonly onBasemap: (mode: BasemapMode) => void
  readonly basemapFailed: boolean
}) {
  return (
    <div className="stack">
      <Segmented
        className="map-basemap"
        ariaLabel="Tampilan peta"
        value={basemap}
        onChange={onBasemap}
        options={BASEMAP_ORDER.map((mode) => ({
          value: mode, label: BASEMAPS[mode].label, title: BASEMAP_HINTS[mode],
        }))}
      />
      {basemapFailed && (
        <p className="map-basemap-error" role="alert">
          {BASEMAPS[basemap].label} gagal dimuat. Coba pilih tampilan lain.
        </p>
      )}
    </div>
  )
}
