import '@/pages/warehouse/warehouseWorkspaces.css'
import type { ReactNode } from 'react'

export function WarehouseFacts({ items }: { items: { label: string; value: ReactNode }[] }) {
  return <dl className="warehouse-record-facts">{items.map(({ label, value }) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}</dl>
}
