import type { ReactNode } from 'react'
import {
  OverlayDrawer,
  DrawerHeader,
  DrawerHeaderTitle,
  DrawerBody,
  Button,
} from '@fluentui/react-components'
import { X } from 'lucide-react'
import { useConfirm } from '@/system'

/**
 * Blade — panel geser-dari-kanan ala Azure Portal, PENGGANTI modal terpusat untuk
 * SEMUA form buat/sunting. Anatomi: header (judul + `X`), body yang bisa di-scroll,
 * dan **footer sticky** (tombol Simpan primary di KIRI, Batal di kanan — konvensi Azure).
 *
 * Ukuran mengikuti kompleksitas form:
 * - `sm`  → form ringkas (<5 field)      → Fluent `medium` (~592px)
 * - `lg`  → form kompleks / banyak seksi  → Fluent `large` (~940px)
 * - `full`→ form sangat lebar / bertab    → Fluent `full` (100%)
 *
 * **Non-modal** (`modalType="non-modal"`) ala Azure Portal: TAK ada scrim yang
 * menutupi konten — daftar di belakang tetap bisa diklik selagi blade terbuka,
 * sehingga memilih baris lain cukup menukar isi blade (data-driven) tanpa menumpuk.
 * ESC / tombol tutup menutup panel; bila form **kotor** (`dirty`) diminta konfirmasi
 * dulu agar perubahan tak hilang tak sengaja. Panel terkendali penuh lewat `open`.
 */
export type BladeSize = 'sm' | 'lg' | 'full'

const FLUENT_SIZE: Record<BladeSize, 'medium' | 'large' | 'full'> = {
  sm: 'medium',
  lg: 'large',
  full: 'full',
}

export function Blade({
  open,
  title,
  subtitle,
  size = 'sm',
  dirty = false,
  onClose,
  footer,
  children,
  className,
}: {
  open: boolean
  title: ReactNode
  subtitle?: ReactNode
  size?: BladeSize
  /** Bila true, ESC/klik-luar/tombol tutup meminta konfirmasi sebelum menutup. */
  dirty?: boolean
  onClose: () => void
  footer?: ReactNode
  children: ReactNode
  /** Kelas tambahan pada drawer — mis. `blade-half` untuk lebar ~50% di desktop. */
  className?: string
}) {
  const confirm = useConfirm()
  const requestClose = () => {
    if (!dirty) {
      onClose()
      return
    }
    // Form kotor → konfirmasi in-app (bukan `window.confirm`) sebelum membuang perubahan.
    void confirm({
      title: 'Tutup panel?',
      message: 'Perubahan belum disimpan akan hilang. Tutup panel?',
      confirmLabel: 'Tutup tanpa simpan',
      danger: true,
    }).then((ok) => {
      if (ok) onClose()
    })
  }

  return (
    <OverlayDrawer
      open={open}
      position="end"
      size={FLUENT_SIZE[size]}
      // Non-modal = tanpa scrim & tanpa focus-trap: konten di belakang tetap
      // interaktif (pola blade Azure). Memilih baris lain cukup menukar isi blade.
      modalType="non-modal"
      className={`azure-blade${className ? ` ${className}` : ''}`}
      // Fluent memicu ini untuk ESC (non-modal: tak ada klik-scrim); kita saring
      // lewat requestClose (form kotor → konfirmasi). Karena `open` terkendali,
      // panel tetap terbuka selama `onClose` tak dipanggil.
      onOpenChange={(_, data) => {
        if (!data.open) requestClose()
      }}
    >
      <DrawerHeader>
        <DrawerHeaderTitle
          action={
            <Button
              appearance="subtle"
              aria-label="Tutup"
              icon={<X size={18} />}
              onClick={requestClose}
            />
          }
        >
          {title}
        </DrawerHeaderTitle>
        {subtitle && <p className="azure-blade-sub">{subtitle}</p>}
      </DrawerHeader>

      <DrawerBody className="azure-blade-body">{children}</DrawerBody>

      {footer && <div className="azure-blade-foot">{footer}</div>}
    </OverlayDrawer>
  )
}
