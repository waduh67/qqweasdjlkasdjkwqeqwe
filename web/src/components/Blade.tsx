import type { ReactNode } from 'react'
import {
  OverlayDrawer,
  DrawerHeader,
  DrawerHeaderTitle,
  DrawerBody,
  Button,
} from '@fluentui/react-components'
import { X } from 'lucide-react'
import { useConfirm } from './ui'

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
 * ESC atau klik luar menutup panel; bila form **kotor** (`dirty`) diminta konfirmasi
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
      className={`azure-blade${className ? ` ${className}` : ''}`}
      // Fluent memicu ini untuk ESC & klik-scrim; kita saring lewat requestClose
      // (form kotor → konfirmasi). Karena `open` terkendali, panel tetap terbuka
      // selama `onClose` tak dipanggil.
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

/**
 * Seksi form berjudul di dalam [Blade] — memisah kelompok field yang panjang
 * (mis. identitas, SNMP, billing) dengan garis pemisah tipis, seperti section
 * pada blade Azure. Judul opsional (`title`); `description` untuk keterangan singkat.
 */
export function FormSection({
  title,
  description,
  children,
}: {
  title?: ReactNode
  description?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="form-section">
      {title && <h3 className="form-section-title">{title}</h3>}
      {description && <p className="form-section-desc">{description}</p>}
      <div className="stack" style={{ gap: '0.75rem' }}>
        {children}
      </div>
    </section>
  )
}

/**
 * Field berlabel — pembungkus tipis `<label><span>…</span>{control}</label>` agar
 * label, teks bantuan, dan pesan galat konsisten di seluruh form. `hint` tampil di
 * bawah kontrol; `error` menggantikan warna & pesan bila ada. `required` memberi
 * tanda bintang.
 */
export function Field({
  label,
  hint,
  error,
  required,
  htmlFor,
  children,
  style,
}: {
  label: ReactNode
  hint?: ReactNode
  error?: ReactNode
  required?: boolean
  htmlFor?: string
  children: ReactNode
  style?: React.CSSProperties
}) {
  return (
    <label className={`field${error ? ' field-error' : ''}`} htmlFor={htmlFor} style={style}>
      <span className="field-label">
        {label}
        {required && <span className="field-req" aria-hidden> *</span>}
      </span>
      {children}
      {error ? (
        <span className="field-msg field-msg-error" role="alert">
          {error}
        </span>
      ) : (
        hint && <span className="field-msg">{hint}</span>
      )}
    </label>
  )
}
