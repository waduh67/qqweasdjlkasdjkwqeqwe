import { Button } from '@/components/atoms'
import { IconCrosshair } from '@/components/atoms/icons'
import type { BasemapMode } from '@/map/basemaps'
import { BasemapPicker } from './BasemapPicker'
import type { ReactNode } from 'react'

export function MapToolbar({ onLocate, basemap, onBasemap, basemapFailed, sitePicker }: {
  onLocate: () => void
  basemap: BasemapMode
  onBasemap: (mode: BasemapMode) => void
  basemapFailed: boolean
  sitePicker?: ReactNode
}) {
  return (
    <div className="map-navigation">
    <div className="map-toolbar map-control-card">
      <div className="map-toolbar-controls">
        <BasemapPicker basemap={basemap} onBasemap={onBasemap} basemapFailed={basemapFailed} />
        <Button variant="subtle" onClick={onLocate}>
          <IconCrosshair size={15} /> Lokasi saya
        </Button>
      </div>
    </div>
    {sitePicker}
    </div>
  )
}
