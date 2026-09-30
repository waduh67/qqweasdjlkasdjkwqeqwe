import { Children, cloneElement, isValidElement, useEffect, useRef, useState, type ComponentProps, type ReactNode } from 'react'
import { Button, Input, Menu, MenuItem, MenuList, MenuPopover, MenuTrigger, Popover, PopoverSurface, PopoverTrigger } from '@fluentui/react-components'
import { Add16Regular, Dismiss12Regular, Search16Regular } from '@fluentui/react-icons'
import { SelectField, TextField } from '@/components/atoms'
import { WarehousePicker } from './warehouse/WarehousePicker'

interface FilterMeta {
  label: string; caption?: string; secondary?: boolean; active?: boolean;
  value?: unknown; defaultValue?: string; from?: string; until?: string; resetKey?: string;
  onRemove?: () => void; autoOpen?: boolean
}

/** The list's search and filter chips share one compact row beneath its commands. */
export function FilterBar({ children, search, label = 'Filter' }: { children: ReactNode; search?: ReactNode; label?: string }) {
  const [added, setAdded] = useState<string[]>([])
  const addButton = useRef<HTMLButtonElement>(null)
  const filters = Children.toArray(children).filter(isValidElement<FilterMeta>)
  const visible = (props: FilterMeta) => !props.secondary || added.includes(props.label) || (props.active ?? ((!!props.value && props.value !== props.defaultValue) || !!props.from || !!props.until))
  const hidden = filters.filter(child => !visible(child.props))
  return <section className="resource-filters resource-filter-bar" aria-label={label}>
    {search}
    {filters.filter(child => visible(child.props)).map(child => cloneElement(child, {
      key: child.props.label, autoOpen: added.includes(child.props.label),
      onRemove: child.props.secondary ? () => { setAdded(current => current.filter(label => label !== child.props.label)); requestAnimationFrame(() => addButton.current?.focus()) } : undefined,
    }))}
    {hidden.length > 0 && <Menu positioning="below-start"><MenuTrigger disableButtonEnhancement><Button ref={addButton} className="resource-add-filter" size="small" appearance="subtle" icon={<Add16Regular />}>Tambah filter</Button></MenuTrigger>
      <MenuPopover><MenuList>{hidden.map(child => <MenuItem key={child.props.label} aria-label={child.props.label} onClick={() => setAdded(current => [...current, child.props.label])}>{child.props.caption ?? child.props.label}</MenuItem>)}</MenuList></MenuPopover>
    </Menu>}
  </section>
}

function FilterChip({ label, caption, summary, clear, onRemove, autoOpen, children, onOpen }: FilterMeta & { summary: ReactNode; clear?: () => void; children: ReactNode | ((close: () => void) => ReactNode); onOpen?: () => void }) {
  const [open, setOpen] = useState(!!autoOpen)
  const trigger = useRef<HTMLButtonElement>(null)
  const remove = () => { clear?.(); setOpen(false); if (onRemove) onRemove(); else trigger.current?.focus() }
  return <div className="resource-filter-chip">
    <Popover open={open} positioning="below-start" onOpenChange={(_, data) => { if (data.open) onOpen?.(); setOpen(data.open) }}>
      <PopoverTrigger disableButtonEnhancement><Button ref={trigger} size="small" appearance="subtle" aria-label={`Filter ${label}`} className="resource-filter-trigger">
        <span>{caption ?? label}: <strong>{summary}</strong></span>
      </Button></PopoverTrigger>
      <PopoverSurface className="resource-filter-popover" aria-label={`Filter ${label}`}>
        <h3>{caption ?? label}</h3>{typeof children === 'function' ? children(() => setOpen(false)) : children}
      </PopoverSurface>
    </Popover>
    {(clear || onRemove) && <Button className="resource-filter-remove" size="small" appearance="subtle" icon={<Dismiss12Regular />} aria-label={`Hapus ${label}`} onClick={remove} />}
  </div>
}

function useFilterSearch(value: string, onChange: (value: string) => void, resetKey?: string) {
  const [text, setText] = useState(value)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const callback = useRef(onChange); callback.current = onChange
  useEffect(() => { clearTimeout(timer.current); setText(value) }, [value])
  useEffect(() => { if (resetKey !== undefined) { clearTimeout(timer.current); setText(value) } }, [resetKey, value])
  useEffect(() => () => clearTimeout(timer.current), [])
  return { text, change: (next: string) => { setText(next); clearTimeout(timer.current); timer.current = setTimeout(() => callback.current(next), 250) } }
}
function SearchInput({ label, text, change, maxLength }: { label: string; text: string; change: (value: string) => void; maxLength: number }) {
  return <Input className="resource-filter-search" size="small" aria-label={label} placeholder={label} value={text} maxLength={maxLength} contentBefore={<Search16Regular />}
    onChange={(_, data) => change(data.value)} />
}
export function FilterSearch({ label, value, onChange, maxLength = 200, resetKey }: { label: string; value: string; onChange: (value: string) => void; maxLength?: number; resetKey?: string }) {
  return <SearchInput label={label} maxLength={maxLength} {...useFilterSearch(value, onChange, resetKey)} />
}

type SelectProps = Omit<ComponentProps<typeof SelectField>, 'label' | 'value' | 'defaultValue' | 'onChange'> & FilterMeta & { value: string; onChange: (value: string) => void }
export function FilterSelect({ label, caption, value, defaultValue = '', secondary: _secondary, active: _active, onRemove, autoOpen, children, onChange, ...props }: SelectProps) {
  const options = Children.toArray(children).filter(isValidElement<{ value: string; children: ReactNode }>)
  const selected = options.find(option => option.props.value === value)
  const reset = options.some(option => option.props.value === defaultValue) && value !== defaultValue
  return <FilterChip label={label} caption={caption} summary={selected?.props.children ?? 'Semua'} onRemove={onRemove} autoOpen={autoOpen}
    clear={reset ? () => onChange(defaultValue) : undefined}>
    {close => <SelectField {...props} label="Nilai" aria-label={label} value={value} onChange={(_, data) => { onChange(data.value); close() }}>{children}</SelectField>}
  </FilterChip>
}

export function FilterPicker<T extends { id: string }>({ label, caption, secondary: _secondary, active: _active, onRemove, autoOpen, ...props }: Parameters<typeof WarehousePicker<T>>[0] & FilterMeta) {
  return <FilterChip label={label} caption={caption} summary={props.value ? props.name(props.value) : 'Semua'} onRemove={onRemove} autoOpen={autoOpen} clear={props.value ? () => props.onChange(null) : undefined}>
    {close => <WarehousePicker {...props} label={label} optional placeholder="Semua" onChange={value => { props.onChange(value); close() }} />}
  </FilterChip>
}

export function FilterText({ label, caption, value, onChange, maxLength = 128, resetKey, secondary: _secondary, active: _active, onRemove, autoOpen }: FilterMeta & { value: string; onChange: (value: string) => void; maxLength?: number }) {
  // Keep the debounce alive when the popover closes; removing the filter still cancels it.
  const search = useFilterSearch(value, onChange, resetKey)
  return <FilterChip label={label} caption={caption} summary={value || 'Semua'} onRemove={onRemove} autoOpen={autoOpen} clear={value ? () => onChange('') : undefined}>
    <SearchInput label={label} maxLength={maxLength} {...search} />
  </FilterChip>
}

function localDate(value?: string, dateTime = false, inclusiveEnd = false) {
  if (!value) return ''
  const date = new Date(value)
  if (inclusiveEnd) date.setDate(date.getDate() - 1)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, -1)
  return dateTime ? local : local.slice(0, 10)
}

/** The interval is published atomically only after both endpoints are valid. */
export function FilterDateRange({ label = 'Tanggal', caption, from, until, onChange, dateTime = false, fromLabel = 'Mulai tanggal', untilLabel = 'Sampai tanggal', resetKey, secondary: _secondary, active: _active, onRemove, autoOpen }: FilterMeta & {
  onChange: (from?: string, until?: string) => void; dateTime?: boolean; fromLabel?: string; untilLabel?: string
}) {
  const [start, setStart] = useState(localDate(from, dateTime)), [end, setEnd] = useState(localDate(until, dateTime, !dateTime)), [error, setError] = useState('')
  useEffect(() => { setStart(localDate(from, dateTime)); setEnd(localDate(until, dateTime, !dateTime)); setError('') }, [from, until, dateTime, resetKey])
  function update(nextStart: string, nextEnd: string) {
    setStart(nextStart); setEnd(nextEnd); setError('')
    if (!nextStart && !nextEnd) { onChange(); return }
    if (!nextStart || !nextEnd) return
    const a = new Date(dateTime ? nextStart : `${nextStart}T00:00:00`), b = new Date(dateTime ? nextEnd : `${nextEnd}T00:00:00`)
    if (!dateTime) b.setDate(b.getDate() + 1)
    if (!Number.isFinite(a.getTime()) || !Number.isFinite(b.getTime()) || a >= b || b.getTime() - a.getTime() > 366 * 86400000) { setError('Isi awal dan akhir yang valid, berurutan, maksimal 366 hari.'); return }
    onChange(a.toISOString(), b.toISOString())
  }
  return <FilterChip label={label} caption={caption} summary={from && until ? `${localDate(from)} – ${localDate(until, false, !dateTime)}` : 'Semua'} onRemove={onRemove} autoOpen={autoOpen}
    onOpen={() => { setStart(localDate(from, dateTime)); setEnd(localDate(until, dateTime, !dateTime)); setError('') }} clear={from || until ? () => { setStart(''); setEnd(''); setError(''); onChange() } : undefined}>
    <div className="resource-filter-period"><TextField label={fromLabel} type={dateTime ? 'datetime-local' : 'date'} step="any" value={start} onChange={(_, data) => update(data.value, end)} />
      <TextField label={untilLabel} type={dateTime ? 'datetime-local' : 'date'} step="any" value={end} onChange={(_, data) => update(start, data.value)} hint={dateTime ? 'Transaksi sebelum waktu ini.' : undefined} />
      {error && <p role="alert">{error}</p>}{!!start !== !!end && <p>Lengkapi kedua tanggal.</p>}
    </div>
  </FilterChip>
}

/** A URL restriction stays visible and removable even without directory access. */
export function FilterToken({ label, caption, value, onClear }: { label: string; caption?: string; value: string; onClear: () => void }) {
  return <div className="resource-filter-chip"><span className="resource-filter-token" title={value}>{caption ?? label}: <strong>{value.slice(0, 8)}…</strong></span>
    <Button className="resource-filter-remove" size="small" appearance="subtle" icon={<Dismiss12Regular />} aria-label={`Hapus ${label}`} onClick={onClear} />
  </div>
}
