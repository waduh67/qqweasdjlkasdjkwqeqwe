import { type BaseUnit, displayUnit, formatBaseQuantity, quantityFromInput } from '@/api/warehouse/quantity'
import { TextField } from '@/components/atoms'

export function WarehouseQuantity({ value, unit }: { value: string; unit: BaseUnit }) {
  return <span className="tnum">{formatBaseQuantity(value, unit)} {displayUnit(unit)}</span>
}

export function WarehouseQuantityField({ label = 'Jumlah', value, unit, onChange, disabled, allowZero = false }: {
  label?: string; value: string; unit: BaseUnit; onChange: (input: string, base: string | null) => void; disabled?: boolean; allowZero?: boolean
}) {
  let base: string | null = null
  let error: string | undefined
  try { base = quantityFromInput(value, unit, allowZero) } catch (caught) { if (value !== '') error = caught instanceof Error ? caught.message : 'Jumlah tidak valid.' }
  return <TextField label={`${label} (${displayUnit(unit)})`} value={value} required disabled={disabled}
    inputMode={unit === 'MM' ? 'decimal' : 'numeric'} autoComplete="off"
    hint={unit === 'MM' ? 'Metre, maksimal 3 angka desimal. Contoh: 82,500.' : 'Jumlah unit bulat.'}
    validationMessage={error} validationState={error ? 'error' : 'none'} aria-invalid={value !== '' && base === null}
    onChange={(_, data) => { let next: string | null = null; try { next = quantityFromInput(data.value, unit, allowZero) } catch { /* retain editable input */ } onChange(data.value, next) }} />
}
