import { Button, Segmented } from '@/components/atoms'
import { IconCrosshair } from '@/components/atoms/icons'
import { BASEMAPS, BASEMAP_HINTS, BASEMAP_ORDER, type BasemapMode } from '@/map/mapStyle'

export function MapToolbar({ onLocate, basemap, onBasemap, basemapFailed }: {
  onLocate: () => void
  basemap: BasemapMode
  onBasemap: (mode: BasemapMode) => void
  basemapFailed: boolean
}) {
  return (
    <div className="map-toolbar">
      <div className="map-toolbar-controls">
        <Segmented
          className="map-basemap"
          ariaLabel="Tampilan peta"
          value={basemap}
          onChange={onBasemap}
          options={BASEMAP_ORDER.map((mode) => ({
            value: mode, label: BASEMAPS[mode].label, title: BASEMAP_HINTS[mode],
          }))}
        />
        <Button variant="subtle" onClick={onLocate}>
          <IconCrosshair size={15} /> Lokasi saya
        </Button>
      </div>
      {basemapFailed && (
        <p className="map-basemap-error" role="alert">
          {BASEMAPS[basemap].label} gagal dimuat. Coba pilih tampilan lain.
        </p>
      )}
    </div>
  )
}
