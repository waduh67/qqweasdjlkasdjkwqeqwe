import { forwardRef } from 'react'
import {
  Button as FluentButton,
  makeStyles,
  mergeClasses,
  tokens,
  type ButtonProps,
  type ForwardRefComponent,
} from '@fluentui/react-components'

/**
 * Tombol standar aplikasi — pembungkus tipis Fluent `Button` supaya SEMUA tombol
 * bergaya dari TEMA Fluent (lihat [azureTheme]), bukan CSS per-elemen. Konvensi kita
 * dipetakan ke `appearance` Fluent lewat prop `variant`:
 *
 * - `default`  → `secondary` (outline netral)  — tombol biasa
 * - `primary`  → `primary`   (terisi biru)      — CTA utama
 * - `subtle`   → `subtle`    (datar, hover abu)  — dulu `.ghost`
 * - `danger`   → `subtle` + warna merah tema     — aksi merusak (Hapus)
 *
 * `danger` tak punya padanan `appearance` di Fluent, jadi diberi kelas merah yang
 * TETAP memakai token tema (bukan warna hardcode) agar konsisten light/dark.
 * Untuk tombol ikon-saja: isi `icon` dan kosongkan children (Fluent otomatis persegi).
 */
export type ButtonVariant = 'default' | 'primary' | 'subtle' | 'danger'

const useStyles = makeStyles({
  danger: {
    color: tokens.colorPaletteRedForeground1,
    ':hover': {
      color: tokens.colorPaletteRedForeground1,
      backgroundColor: tokens.colorPaletteRedBackground1,
    },
    ':hover:active': {
      color: tokens.colorPaletteRedForeground2,
      backgroundColor: tokens.colorPaletteRedBackground2,
    },
  },
})

const VARIANT_APPEARANCE: Record<ButtonVariant, ButtonProps['appearance']> = {
  default: 'secondary',
  primary: 'primary',
  subtle: 'subtle',
  danger: 'subtle',
}

export type AppButtonProps = Omit<ButtonProps, 'appearance'> & { variant?: ButtonVariant }

export const Button: ForwardRefComponent<AppButtonProps> = forwardRef(
  ({ variant = 'default', className, ...rest }, ref) => {
    const styles = useStyles()
    // `ButtonProps` Fluent polimorfik (`as: 'a' | 'button'`), jadi hasil destrukturisasi
    // `...rest` melebarkan handler (onCopy/onChange dll.) menjadi union lintas-elemen yang
    // tak bisa disebar balik. Merakit ulang objek lalu meng-cast-nya ke `ButtonProps`
    // mengembalikannya ke union bersih yang diterima Fluent — API publik tetap terketik.
    const fluentProps = {
      ...rest,
      appearance: VARIANT_APPEARANCE[variant],
      className: variant === 'danger' ? mergeClasses(styles.danger, className) : className,
    } as ButtonProps
    return <FluentButton ref={ref} {...fluentProps} />
  },
)
