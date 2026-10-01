import { Button } from '@/components/atoms'
import { IconCrosshair } from '@/components/atoms/icons'
import type { BasemapMode } from '@/map/basemaps'
import { BasemapPicker } from './BasemapPicker'

export function MapToolbar({ onLocate, basemap, onBasemap, basemapFailed }: {
  onLocate: () => void
  basemap: BasemapMode
  onBasemap: (mode: BasemapMode) => void
  basemapFailed: boolean
}) {
  return (
    <div className="map-toolbar">
      <div className="map-toolbar-controls">
        <BasemapPicker basemap={basemap} onBasemap={onBasemap} basemapFailed={basemapFailed} />
        <Button variant="subtle" onClick={onLocate}>
          <IconCrosshair size={15} /> Lokasi saya
        </Button>
      </div>
    </div>
  )
}
