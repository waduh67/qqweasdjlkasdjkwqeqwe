import type { ReactNode } from 'react'
import { ToggleButton, makeStyles, mergeClasses, tokens } from '@fluentui/react-components'

/**
 * Kontrol tersegmen (pemilih toggle) — grup pilihan saling-eksklusif dalam satu
 * "track" abu, pilihan aktif tampil terangkat putih. Menggantikan pola native
 * `<div className="segment"><button className="active">` supaya gaya datang dari
 * TEMA Fluent (dibangun atas `ToggleButton`, ber-kelas `fui-`) — bukan CSS `.segment`
 * per-elemen di index.css, dan tak tersentuh aturan tombol native global.
 */
const useStyles = makeStyles({
  root: {
    display: 'inline-flex',
    width: 'fit-content',
    padding: '2px',
    gap: '2px',
    backgroundColor: tokens.colorNeutralBackground3,
    border: `1px solid ${tokens.colorNeutralStroke2}`,
    borderRadius: tokens.borderRadiusMedium,
  },
  btn: {
    minWidth: 'auto',
    border: 'none',
    fontWeight: tokens.fontWeightRegular,
  },
  // Pilihan aktif: latar putih + bayangan tipis (efek "terangkat") ala segmented Azure.
  checked: {
    backgroundColor: tokens.colorNeutralBackground1,
    boxShadow: tokens.shadow2,
    ':hover': { backgroundColor: tokens.colorNeutralBackground1 },
  },
})

export function Segmented<T extends string | number>({
  options,
  value,
  onChange,
  ariaLabel,
  className,
  disabled,
}: {
  options: { value: T; label: ReactNode }[]
  value: T
  onChange: (value: T) => void
  ariaLabel?: string
  className?: string
  disabled?: boolean
}) {
  const s = useStyles()
  return (
    <div className={mergeClasses(s.root, className)} role="group" aria-label={ariaLabel}>
      {options.map((o) => {
        const active = o.value === value
        return (
          <ToggleButton
            key={String(o.value)}
            size="small"
            appearance="subtle"
            checked={active}
            disabled={disabled}
            onClick={() => onChange(o.value)}
            className={active ? mergeClasses(s.btn, s.checked) : s.btn}
          >
            {o.label}
          </ToggleButton>
        )
      })}
    </div>
  )
}
