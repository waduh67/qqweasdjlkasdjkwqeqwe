import { useCallback } from 'react'
import { Field } from '@fluentui/react-components'
import { api } from '@/api/client'
import type { DiscoveredOnuView } from '@/api/monitoring'
import { Combobox } from '@/components/molecules/Combobox'

export function MigrationOnuPicker({ value, onChange, required = false }: {
  value: DiscoveredOnuView | null; onChange: (onu: DiscoveredOnuView | null) => void; required?: boolean
}) {
  const load = useCallback(async (term: string) => {
    const rows = await api.get<DiscoveredOnuView[]>('/api/monitoring/discovered-onus')
    const query = term.trim().toLowerCase()
    return rows.filter(row => `${row.serialNumber} ${row.oltCode} ${row.ponPortLabel ?? ''}`.toLowerCase().includes(query))
  }, [])
  return <Field label={required ? 'ONU terpasang' : 'ONU terpasang (opsional)'} required={required}>
    <Combobox value={value?.id ?? ''} initialLabel={value?.serialNumber} onChange={(_, onu) => onChange(onu)}
      fetchOptions={load} toId={row => row.id} toLabel={row => row.serialNumber}
      toMeta={row => `${row.oltCode}${row.ponPortLabel ? ` · ${row.ponPortLabel}` : ''}`}
      ariaLabel="ONU terpasang" placeholder="Cari nomor seri, OLT, atau PON" selectionRequired={required}
      emptyText="Tidak ada ONU yang tersedia" />
  </Field>
}
