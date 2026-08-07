import type { ReactNode } from 'react'

/**
 * Strip tab untuk memecah panel padat (mis. detail work order) jadi bagian yang
 * terpisah tapi tetap satu konteks — lebih terbaca ketimbang satu kolom panjang.
 * Terkendali penuh: pemanggil memegang tab aktif. `badge` opsional untuk hitungan.
 */
export function Tabs<T extends string>({
  tabs,
  active,
  onChange,
}: {
  tabs: { key: T; label: ReactNode; badge?: ReactNode }[]
  active: T
  onChange: (key: T) => void
}) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button
          key={t.key}
          type="button"
          role="tab"
          aria-selected={active === t.key}
          className={`tab${active === t.key ? ' active' : ''}`}
          onClick={() => onChange(t.key)}
        >
          {t.label}
          {t.badge != null && <span className="tab-badge">{t.badge}</span>}
        </button>
      ))}
    </div>
  )
}
