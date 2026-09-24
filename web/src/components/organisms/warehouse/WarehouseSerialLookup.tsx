import { useRef, useState, type ReactNode } from 'react'
import { lookupIdentity } from '@/api/warehouse/masters'
import type { WarehouseIdentity } from '@/api/warehouse/models'
import { Button, TextField } from '@/components/atoms'
import { warehouseError } from '@/api/warehouse/errors'

/** Keyboard scanners and manual entry share this lookup. Only an explicit parent action may post stock. */
export function WarehouseSerialLookup({ onSelect, candidate, disabled = false }: { onSelect: (identity: WarehouseIdentity | null) => void; candidate?: ReactNode; disabled?: boolean }) {
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const active = useRef(false)
  async function lookup() {
    if (disabled || active.current || value.trim() === '') return
    active.current = true
    setBusy(true); setError(null); onSelect(null)
    try { onSelect(await lookupIdentity(value)) } catch (caught) { setError(warehouseError(caught)) }
    finally { active.current = false; setBusy(false) }
  }
  return <div className="stack" style={{ gap: '0.5rem' }}>
    <TextField label="Serial atau MAC" hint="Ketik atau pindai lalu tekan Enter untuk mencari perangkat." value={value} maxLength={128} disabled={disabled || busy}
      onChange={(_, data) => { setValue(data.value); setError(null); onSelect(null) }}
      onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); void lookup() } }} />
    <Button disabled={disabled || busy || !value.trim()} onClick={() => void lookup()}>{busy ? 'Mencari…' : 'Cari perangkat'}</Button>
    {error && <p role="alert" className="error">{error}</p>}
    {candidate}
  </div>
}
