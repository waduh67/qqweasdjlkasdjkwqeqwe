import { useEffect, useMemo, useRef, useState, type ChangeEvent, type MouseEvent } from 'react'
import { Combobox as FluentCombobox, Option, OptionGroup, Spinner, Text } from '@fluentui/react-components'
import { IconClose } from '@/components/atoms/icons'
import { Button } from '@/components/atoms'

/**
 * Pemilih tunggal yang bisa dicari (combobox/typeahead) untuk opsi yang banyak.
 * Opsi diambil lewat [fetchOptions], sehingga pemanggil bebas menggunakan pencarian
 * sisi-server atau filter lokal tanpa komponen ini mengetahui perbedaannya.
 *
 * Terkendali lewat [value] (id terpilih, '' = kosong). Label pilihan disimpan internal
 * karena opsi dapat datang secara asinkron; [initialLabel] mengisi label nilai awal yang
 * belum tersedia sebagai item. Navigasi papan ketik dan aksesibilitas listbox ditangani
 * oleh Fluent UI React v9.
 */
export interface ComboboxProps<T> {
  value: string
  onChange: (id: string, item: T | null) => void
  /** Ambil opsi untuk sebuah kata kunci — server-search ATAU filter lokal, terserah pemanggil. */
  fetchOptions: (term: string) => Promise<T[]>
  toId: (item: T) => string
  /** Label utama opsi + teks terpilih saat kolom tertutup. */
  toLabel: (item: T) => string
  /** Baris kedua opsi (kode/telepon/alamat) — opsional. */
  toMeta?: (item: T) => string | undefined
  /** Kelompokkan opsi; pemanggil wajib mengurutkan opsi berdasarkan grup lebih dulu. */
  groupOf?: (item: T) => string
  /** Label terpilih awal saat [value] sudah terisi tapi itemnya belum di tangan. */
  initialLabel?: string
  placeholder?: string
  disabled?: boolean
  /** Debounce fetch (ms). Beri 0 untuk filter lokal yang instan. Default 250. */
  debounceMs?: number
  emptyText?: string
}

interface OptionGroupData<T> {
  label: string
  items: T[]
}

export function Combobox<T>({
  value,
  onChange,
  fetchOptions,
  toId,
  toLabel,
  toMeta,
  groupOf,
  initialLabel = '',
  placeholder,
  disabled,
  debounceMs = 250,
  emptyText = 'Tak ada hasil',
}: ComboboxProps<T>) {
  const [open, setOpen] = useState(false)
  const [term, setTerm] = useState('')
  const [options, setOptions] = useState<T[]>([])
  const [loading, setLoading] = useState(false)
  const [label, setLabel] = useState(initialLabel)
  const containerRef = useRef<HTMLDivElement>(null)
  const fetchRef = useRef(fetchOptions)
  const requestRef = useRef(0)

  fetchRef.current = fetchOptions

  useEffect(() => {
    if (!value) setLabel('')
  }, [value])

  useEffect(() => {
    if (initialLabel) setLabel(initialLabel)
  }, [initialLabel])

  useEffect(() => {
    if (!open) return

    const handleOutside = (event: Event) => {
      const target = event.target as HTMLElement | null
      if (!target) return

      const trigger = containerRef.current?.querySelector('.fui-Combobox') ?? containerRef.current
      if (trigger && trigger.contains(target)) return
      if (target.closest?.('[role="listbox"]')) return

      setOpen(false)
    }

    window.addEventListener('pointerdown', handleOutside, true)
    window.addEventListener('mousedown', handleOutside, true)
    window.addEventListener('touchstart', handleOutside, true)

    return () => {
      window.removeEventListener('pointerdown', handleOutside, true)
      window.removeEventListener('mousedown', handleOutside, true)
      window.removeEventListener('touchstart', handleOutside, true)
    }
  }, [open])

  useEffect(() => {
    if (!open) return

    const request = ++requestRef.current
    const delay = term.trim() ? debounceMs : 0
    setLoading(true)
    const timeout = window.setTimeout(async () => {
      try {
        const fetchedOptions = await fetchRef.current(term.trim())
        if (request === requestRef.current) setOptions(fetchedOptions)
      } catch {
        if (request === requestRef.current) setOptions([])
      } finally {
        if (request === requestRef.current) setLoading(false)
      }
    }, delay)

    return () => {
      window.clearTimeout(timeout)
      requestRef.current += 1
    }
  }, [debounceMs, open, term])

  const groupedOptions = useMemo<OptionGroupData<T>[]>(() => {
    if (!groupOf) return []

    return options.reduce<OptionGroupData<T>[]>((groups, item) => {
      const groupLabel = groupOf(item)
      const previousGroup = groups[groups.length - 1]
      if (!previousGroup || previousGroup.label !== groupLabel) {
        groups.push({ label: groupLabel, items: [item] })
      } else {
        previousGroup.items.push(item)
      }
      return groups
    }, [])
  }, [groupOf, options])

  const choose = (item: T) => {
    setLabel(toLabel(item))
    onChange(toId(item), item)
    setOpen(false)
    setTerm('')
  }

  const clear = () => {
    onChange('', null)
    setLabel('')
    setTerm('')
    setOpen(false)
  }

  return (
    <div ref={containerRef} className={`combobox${disabled ? ' is-disabled' : ''}`}>
      <FluentCombobox
        className="cb-field"
        open={open}
        value={open ? term : label}
        selectedOptions={value ? [value] : []}
        placeholder={label || placeholder}
        disabled={disabled}
        autoComplete="off"
        onOpenChange={(_, data) => setOpen(data.open)}
        onChange={(event: ChangeEvent<HTMLInputElement>) => {
          setTerm(event.target.value)
          setOpen(true)
        }}
        onOptionSelect={(_, data) => {
          const item = options.find((candidate) => toId(candidate) === data.optionValue)
          if (item) choose(item)
        }}
        expandIcon={loading ? <Spinner size="tiny" /> : undefined}
      >
        {loading && options.length === 0 ? (
          <Option disabled value="__loading__" text="Memuat">
            <span className="cb-note">
              <Spinner size="tiny" /> Memuat…
            </span>
          </Option>
        ) : options.length === 0 ? (
          <Option disabled value="__empty__" text={emptyText}>
            <Text as="span" className="cb-note">{emptyText}</Text>
          </Option>
        ) : groupOf ? (
          groupedOptions.map((group, groupIndex) => (
            <OptionGroup key={`${group.label}-${groupIndex}`} label={group.label}>
              {group.items.map((item) => {
                const itemLabel = toLabel(item)
                const meta = toMeta?.(item)
                return (
                  <Option key={toId(item)} value={toId(item)} text={itemLabel}>
                    <Text as="span" className="cb-label" size={300}>{itemLabel}</Text>
                    {meta && <Text as="span" className="cb-meta" size={200}>{meta}</Text>}
                  </Option>
                )
              })}
            </OptionGroup>
          ))
        ) : (
          options.map((item) => {
            const itemLabel = toLabel(item)
            const meta = toMeta?.(item)
            return (
              <Option key={toId(item)} value={toId(item)} text={itemLabel}>
                <Text as="span" className="cb-label" size={300}>{itemLabel}</Text>
                {meta && <Text as="span" className="cb-meta" size={200}>{meta}</Text>}
              </Option>
            )
          })
        )}
      </FluentCombobox>
      {value && !loading && (
        <Button
          variant="subtle"
          size="small"
          icon={<IconClose size={15} />}
          onMouseDown={(event: MouseEvent) => event.preventDefault()}
          onClick={clear}
          aria-label="Hapus pilihan"
        />
      )}
    </div>
  )
}
