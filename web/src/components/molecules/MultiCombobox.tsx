import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Combobox, Option, Spinner, Text, type OptionOnSelectData } from '@fluentui/react-components'

/**
 * Pemilih JAMAK yang bisa dicari untuk kasus banyak nilai, misalnya roster
 * teknisi pada satu work order. Nilai tetap dikendalikan oleh pemanggil lewat
 * [values], sementara label disimpan lokal agar nilai yang belum kembali dari
 * pencarian server tetap dapat dikenali oleh Combobox.
 */
export interface MultiComboboxProps<T> {
  values: string[]
  onChange: (ids: string[]) => void
  /** Ambil opsi untuk sebuah kata kunci — server-search ATAU filter lokal, terserah pemanggil. */
  fetchOptions: (term: string) => Promise<T[]>
  toId: (item: T) => string
  /** Label utama opsi. */
  toLabel: (item: T) => string
  /** Baris kedua opsi (kode/telepon/dll) — opsional. */
  toMeta?: (item: T) => string | undefined
  /** Label awal untuk [values] yang itemnya belum di tangan (mis. roster tersimpan). */
  initialLabels?: Record<string, string>
  placeholder?: string
  disabled?: boolean
  /** Debounce fetch (ms). Beri 0 untuk filter lokal yang instan. Default 250. */
  debounceMs?: number
  emptyText?: string
}

export function MultiCombobox<T>({
  values,
  onChange,
  fetchOptions,
  toId,
  toLabel,
  toMeta,
  initialLabels,
  placeholder,
  disabled,
  debounceMs = 250,
  emptyText = 'Tak ada hasil',
}: MultiComboboxProps<T>) {
  const [open, setOpen] = useState(false)
  const [term, setTerm] = useState('')
  const [options, setOptions] = useState<T[]>([])
  const [loading, setLoading] = useState(false)
  const [labels, setLabels] = useState<Record<string, string>>(initialLabels ?? {})
  const containerRef = useRef<HTMLDivElement>(null)
  const requestId = useRef(0)

  useEffect(() => {
    if (initialLabels) setLabels((current) => ({ ...current, ...initialLabels }))
  }, [initialLabels])

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

  // Setiap pencarian diberi urutan agar respons server yang lambat tidak menimpa hasil terbaru.
  useEffect(() => {
    if (!open) return

    const currentRequest = ++requestId.current
    const timer = window.setTimeout(async () => {
      setLoading(true)
      try {
        const fetched = await fetchOptions(term.trim())
        if (requestId.current !== currentRequest) return

        setOptions(fetched)
        setLabels((current) => {
          const next = { ...current }
          for (const item of fetched) next[toId(item)] = toLabel(item)
          return next
        })
      } catch {
        if (requestId.current === currentRequest) setOptions([])
      } finally {
        if (requestId.current === currentRequest) setLoading(false)
      }
    }, debounceMs)

    return () => {
      window.clearTimeout(timer)
    }
  }, [debounceMs, fetchOptions, open, term, toId, toLabel])

  const handleOptionSelect = (_: unknown, data: OptionOnSelectData) => {
    const selectedOptions = data.selectedOptions
    const item = options.find((option) => toId(option) === data.optionValue)
    if (item) {
      const id = toId(item)
      setLabels((current) => ({ ...current, [id]: toLabel(item) }))
    }
    onChange(selectedOptions)
    setTerm('')
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Backspace' && !term && values.length > 0) {
      // Fluent menangani navigasi dan penghapusan pilihan melalui listbox; ini
      // mempertahankan kebiasaan tag-input untuk melepas pilihan terakhir.
      onChange(values.slice(0, -1))
    }
  }

  const optionIds = new Set(options.map(toId))

  return (
    <div ref={containerRef} className="multi-combobox">
      <Combobox
        multiselect
        disabled={disabled}
        open={open}
        selectedOptions={values}
        value={term}
        placeholder={placeholder}
        onOpenChange={(_, data) => setOpen(data.open)}
        onChange={(event) => setTerm(event.target.value)}
        onOptionSelect={handleOptionSelect}
        onKeyDown={handleKeyDown}
        aria-label={placeholder ?? 'Pilih beberapa opsi'}
        expandIcon={loading ? <Spinner size="tiny" /> : undefined}
      >
        {loading && options.length === 0 ? (
          <Option disabled value="__loading__" text="Memuat">
            <Text size={300}>Memuat…</Text>
          </Option>
        ) : options.length === 0 ? (
          <Option disabled value="__empty__" text={emptyText}>
            <Text size={300}>{emptyText}</Text>
          </Option>
        ) : (
          options.map((item) => {
            const id = toId(item)
            const meta = toMeta?.(item)
            return (
              <Option key={id} value={id} text={toLabel(item)}>
                <Text as="span" size={300}>{toLabel(item)}</Text>
                {meta && <Text as="span" size={200}>{meta}</Text>}
              </Option>
            )
          })
        )}

        {/* Opsi terseleksi yang tidak ada pada respons saat ini tetap terdaftar agar label dan state Fluent konsisten. */}
        {values.filter((id) => !optionIds.has(id)).map((id) => (
          <Option key={id} value={id} text={labels[id] ?? id} style={{ display: 'none' }} />
        ))}
      </Combobox>
    </div>
  )
}
