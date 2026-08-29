import { useEffect } from 'react'
import { Menu, MenuItem, MenuList, MenuPopover, MenuTrigger } from '@fluentui/react-components'
import { Button } from '@/components/atoms'
import { IconCrosshair, IconCustomers, IconPlus } from '@/components/atoms/icons'
import { ASSET_META, type AssetKind } from '@/map/mapAssets'
import { useToast } from '@/system'

/**
 * Menu "tambah di sini": daftar yang bisa dibuat PADA titik yang barusan ditunjuk.
 * Muncul di titik itu juga, bukan di pojok layar — supaya hubungan "yang ini, di
 * sini" tak perlu diingat-ingat operator. Kosong kalau operator tak berizin membuat
 * apa pun; pemanggil yang memutuskan tak menampilkannya sama sekali.
 *
 * Menutup lewat Escape & klik di luar (peta sendiri menutupnya lewat handler klik).
 */
export function AddHereMenu({
  at,
  can,
  onPick,
  onSurvey,
  onClose,
}: {
  at: { lng: number; lat: number; x: number; y: number }
  can: (perm: string) => boolean
  onPick: (kind: AssetKind | 'CUSTOMER') => void
  onSurvey: () => void
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const toast = useToast()
  const assets = (Object.keys(ASSET_META) as AssetKind[]).filter((k) => can(ASSET_META[k].createPerm))
  // Menaruh pelanggan = memberi koordinat pada pelanggan yang SUDAH ada (impor massal
  // menaruhnya di 0,0), jadi izinnya "ubah pelanggan", bukan "buat pelanggan".
  const canPlaceCustomer = can('customer.customer.update')
  // Mengecek kapasitas tidak mengubah apa pun, jadi izinnya cukup "lihat ODP" —
  // dan justru orang yang tak boleh menambah aset (sales) yang paling sering
  // menanyakannya.
  const canSurvey = can('network.odp.view')
  if (assets.length === 0 && !canPlaceCustomer && !canSurvey) return null

  return (
    <Menu open positioning={{ position: 'below', align: 'start' }}>
      <MenuTrigger disableButtonEnhancement>
        <Button
          className="map-menu-head tnum"
          title="Klik untuk menyalin koordinat"
          style={{ left: at.x, top: at.y }}
          onClick={() => {
            const text = `${at.lat.toFixed(6)}, ${at.lng.toFixed(6)}`
            void navigator.clipboard?.writeText(text).then(() => toast.success('Koordinat disalin'))
          }}
        >
          {at.lat.toFixed(6)}, {at.lng.toFixed(6)}
        </Button>
      </MenuTrigger>
      <MenuPopover className="map-menu" style={{ left: at.x, top: at.y }}>
        <MenuList>
          {assets.map((k) => (
            <MenuItem key={k} className="map-menu-item" icon={<IconPlus size={15} />} onClick={() => onPick(k)}>
              {ASSET_META[k].label}
            </MenuItem>
          ))}
          {canPlaceCustomer && (
            <MenuItem
              className="map-menu-item"
              icon={<IconCustomers size={15} />}
              title="Pelanggan hasil impor yang belum punya titik di peta"
              onClick={() => onPick('CUSTOMER')}
            >
              Pelanggan belum berkoordinat
            </MenuItem>
          )}
          {canSurvey && (
            <MenuItem
              className="map-menu-item"
              icon={<IconCrosshair size={15} />}
              title="Kotak siap pakai & core menganggur di sekitar titik ini"
              onClick={onSurvey}
            >
              Cek kapasitas di sini
            </MenuItem>
          )}
        </MenuList>
      </MenuPopover>
    </Menu>
  )
}
