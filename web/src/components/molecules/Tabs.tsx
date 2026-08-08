import type { ReactNode } from 'react'
import { TabList, Tab, type SelectTabData } from '@fluentui/react-components'

/**
 * Strip tab untuk memecah panel padat (mis. detail work order) jadi bagian yang
 * terpisah tapi tetap satu konteks — lebih terbaca ketimbang satu kolom panjang.
 * Terkendali penuh: pemanggil memegang tab aktif. `badge` opsional untuk hitungan.
 *
 * Dibungkus dari Fluent `TabList`/`Tab` agar gaya (garis-bawah aktif, hover, fokus)
 * datang dari TEMA Fluent — bukan CSS `.tabs`/`.tab` per-elemen.
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
    <TabList
      selectedValue={active}
      onTabSelect={(_, data: SelectTabData) => onChange(data.value as T)}
    >
      {tabs.map((t) => (
        <Tab key={t.key} value={t.key}>
          {t.label}
          {t.badge != null && <span className="tab-badge">{t.badge}</span>}
        </Tab>
      ))}
    </TabList>
  )
}
