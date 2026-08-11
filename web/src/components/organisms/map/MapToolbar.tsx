import { Button } from '@/components/atoms'
import { IconCrosshair } from '@/components/atoms/icons'

/**
 * Toolbar kiri-atas. Tinggal satu tombol: menambah perangkat kini lewat menu klik
 * kanan / tahan-lama di titik yang dituju, sehingga peta tak lagi dipenuhi tombol
 * yang semuanya berakhir dengan "sekarang klik lokasinya".
 */
export function MapToolbar({ onLocate }: { onLocate: () => void }) {
  return (
    <div className="map-toolbar">
      <Button variant="subtle" onClick={onLocate}>
        <IconCrosshair size={15} /> Lokasi saya
      </Button>
    </div>
  )
}
