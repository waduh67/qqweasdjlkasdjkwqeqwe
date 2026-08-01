import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { IconClose, IconSearch } from './icons'
import { Spinner } from './ui'

/**
 * Pemilih JAMAK yang bisa dicari — saudara [Combobox] untuk kasus "banyak nilai"
 * (mis. roster teknisi tim datar pada satu work order). Nilai terpilih tampil sebagai
 * chip di atas kolom; mengetik menyaring opsi, memilih menambah chip (dropdown tetap
 * terbuka agar bisa menambah lagi), dan yang sudah terpilih disembunyikan dari daftar.
 *
 * Terkendali lewat [values] (daftar id). Karena opsi datang async, label chip dipegang
 * internal: di-set saat memilih dan bisa di-seed lewat [initialLabels] untuk nilai yang
 * sudah terisi sejak awal (roster tersimpan). Backspace di kolom kosong melepas chip
 * terakhir. Navigasi keyboard (↑/↓/Enter/Esc) dan klik-di-luar untuk menutup.
 */
export interface MultiComboboxProps<T> {
  values: string[]
  onChange: (ids: string[]) => void
  /** Ambil opsi untuk sebuah kata kunci — server-search ATAU filter lokal, terserah pemanggil. */
  fetchOptions: (term: string) => Promise<T[]>
  toId: (item: T) => string
  /** Label utama opsi + teks chip. */
  toLabel: (item: T) => string
  /** Baris kedua opsi (kode/telepon/dll) — opsional. */
  toMeta?: (item: T) => string | undefined
  /** Label chip awal untuk [values] yang itemnya belum di tangan (mis. roster tersimpan). */
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
  const [active, setActive] = useState(-1)
  // Label chip terkumpul: di-seed dari initialLabels, ditambah saat memilih / opsi termuat.
  const [labels, setLabels] = useState<Record<string, string>>(initialLabels ?? {})

  const fetchRef = useRef(fetchOptions)
  fetchRef.current = fetchOptions
  const rootRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const activeRef = useRef<HTMLLIElement>(null)

  // Yang sudah terpilih disembunyikan dari daftar (tak bisa dipilih dua kali).
  const visible = options.filter((o) => !values.includes(toId(o)))

  // Ambil opsi saat terbuka / kata kunci berubah; didebounce, permintaan lama dibatalkan.
  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    const run = async () => {
      try {
        const opts = await fetchRef.current(term.trim())
        if (cancelled) return
        setOptions(opts)
        setActive(opts.length ? 0 : -1)
        // Rekam label agar chip nilai yang termuat lewat pencarian tetap bernama.
        setLabels((prev) => {
          const next = { ...prev }
          for (const o of opts) next[toId(o)] = toLabel(o)
          return next
        })
      } catch {
        if (!cancelled) {
          setOptions([])
          setActive(-1)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    const t = window.setTimeout(run, debounceMs)
    return () => {
      cancelled = true
      window.clearTimeout(t)
    }
  }, [open, term, debounceMs, toId, toLabel])

  // Klik di luar menutup dropdown.
  useEffect(() => {
    if (!open) return
    const onDocClick = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [open])

  // Jaga opsi aktif tetap terlihat saat navigasi keyboard.
  useEffect(() => {
    activeRef.current?.scrollIntoView({ block: 'nearest' })
  }, [active])

  const add = (item: T) => {
    const id = toId(item)
    setLabels((prev) => ({ ...prev, [id]: toLabel(item) }))
    if (!values.includes(id)) onChange([...values, id])
    setTerm('')
    inputRef.current?.focus()
  }

  const remove = (id: string) => {
    onChange(values.filter((v) => v !== id))
    inputRef.current?.focus()
  }

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (!open) setOpen(true)
      else setActive((i) => Math.min(i + 1, visible.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      if (open && active >= 0 && visible[active]) {
        e.preventDefault()
        add(visible[active])
      }
    } else if (e.key === 'Escape') {
      if (open) {
        e.preventDefault()
        setOpen(false)
      }
    } else if (e.key === 'Backspace' && !term && values.length > 0) {
      // Kolom kosong + Backspace = lepas chip terakhir (kebiasaan tag-input).
      remove(values[values.length - 1])
    }
  }

  return (
    <div className={`combobox${disabled ? ' is-disabled' : ''}`} ref={rootRef}>
      {values.length > 0 && (
        <div className="row wrap" style={{ gap: '0.35rem', marginBottom: '0.35rem' }}>
          {values.map((id) => (
            <span
              key={id}
              className="badge accent"
              style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem' }}
            >
              {labels[id] ?? id}
              {!disabled && (
                <button
                  type="button"
                  className="ghost icon-btn"
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => remove(id)}
                  aria-label="Lepas teknisi"
                >
                  <IconClose size={12} />
                </button>
              )}
            </span>
          ))}
        </div>
      )}

      <div className="cb-field">
        <IconSearch size={16} />
        <input
          ref={inputRef}
          type="text"
          role="combobox"
          aria-expanded={open}
          autoComplete="off"
          disabled={disabled}
          value={term}
          placeholder={placeholder}
          onChange={(e) => {
            setTerm(e.target.value)
            if (!open) setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
        />
        {loading && <Spinner />}
      </div>

      {open && (
        // preventDefault pada mousedown menu → klik opsi tak mem-blur input duluan.
        <ul className="cb-menu" onMouseDown={(e) => e.preventDefault()}>
          {loading && visible.length === 0 ? (
            <li className="cb-note">Memuat…</li>
          ) : visible.length === 0 ? (
            <li className="cb-note">{emptyText}</li>
          ) : (
            visible.map((item, i) => {
              const id = toId(item)
              const meta = toMeta?.(item)
              return (
                <li
                  key={id}
                  ref={i === active ? activeRef : undefined}
                  className={`cb-option${i === active ? ' active' : ''}`}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => add(item)}
                >
                  <span className="cb-label">{toLabel(item)}</span>
                  {meta && <span className="cb-meta">{meta}</span>}
                </li>
              )
            })
          )}
        </ul>
      )}
    </div>
  )
}
