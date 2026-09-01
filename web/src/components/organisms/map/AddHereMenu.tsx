import { useEffect, useRef } from 'react'
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
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    const onDocClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    window.addEventListener('mousedown', onDocClick)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('mousedown', onDocClick)
    }
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
    <div
      ref={menuRef}
      className="map-menu"
      style={{ left: at.x, top: at.y }}
      onClick={(e) => e.stopPropagation()}
    >
      <button
        type="button"
        className="map-menu-head tnum"
        title="Klik untuk menyalin koordinat"
        onClick={() => {
          const text = `${at.lat.toFixed(6)}, ${at.lng.toFixed(6)}`
          void navigator.clipboard?.writeText(text).then(() => toast.success('Koordinat disalin'))
        }}
      >
        {at.lat.toFixed(6)}, {at.lng.toFixed(6)}
      </button>
      {assets.map((k) => (
        <button
          key={k}
          type="button"
          className="map-menu-item"
          onClick={() => onPick(k)}
        >
          <IconPlus size={15} />
          {ASSET_META[k].label}
        </button>
      ))}
      {canPlaceCustomer && (
        <button
          type="button"
          className="map-menu-item"
          title="Pelanggan hasil impor yang belum punya titik di peta"
          onClick={() => onPick('CUSTOMER')}
        >
          <IconCustomers size={15} />
          Pelanggan belum berkoordinat
        </button>
      )}
      {canSurvey && (
        <button
          type="button"
          className="map-menu-item"
          title="Kotak siap pakai & core menganggur di sekitar titik ini"
          onClick={onSurvey}
        >
          <IconCrosshair size={15} />
          Cek kapasitas di sini
        </button>
      )}
    </div>
  )
}
