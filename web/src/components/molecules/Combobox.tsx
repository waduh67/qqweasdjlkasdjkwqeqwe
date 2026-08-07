import { Fragment, useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { IconClose, IconSearch } from '@/components/atoms/icons'
import { Spinner } from '@/components/atoms'

/**
 * Pemilih tunggal yang bisa dicari (combobox/typeahead) — pengganti proper untuk
 * `<select>` polos saat opsinya bisa ribuan (pelanggan) atau sekadar biar enak dicari
 * (teknisi). Ketik untuk menyaring; opsi diambil lewat [fetchOptions] sehingga pemanggil
 * bebas memakai **pencarian sisi-server** (pelanggan) atau **filter lokal** (daftar teknisi
 * yang sudah dimuat) tanpa komponen ini tahu bedanya.
 *
 * Terkendali lewat [value] (id terpilih, '' = kosong). Karena opsi datang async, label
 * yang tampil saat tertutup dipegang internal: di-set saat memilih, dan bisa di-seed lewat
 * [initialLabel] untuk kasus nilai sudah terisi sejak awal (mis. teknisi yang sudah
 * ditugaskan). Mendukung navigasi keyboard (↑/↓/Enter/Esc) dan klik-di-luar untuk menutup.
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
  /**
   * Kelompokkan opsi: kembalikan label grup sebuah item. Bila diberikan, header grup dirender saat
   * label berubah antar item berurutan — jadi pemanggil harus MENGURUT opsi per grup lebih dulu.
   * Header non-interaktif & tak memengaruhi navigasi keyboard.
   */
  groupOf?: (item: T) => string
  /** Label terpilih awal saat [value] sudah terisi tapi itemnya belum di tangan. */
  initialLabel?: string
  placeholder?: string
  disabled?: boolean
  /** Debounce fetch (ms). Beri 0 untuk filter lokal yang instan. Default 250. */
  debounceMs?: number
  emptyText?: string
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
  const [active, setActive] = useState(-1)
  const [label, setLabel] = useState(initialLabel)

  // Simpan fetchOptions di ref agar efek ambil-data tak bergantung pada identitas fungsi
  // (pemanggil boleh mengoper arrow inline tanpa memicu loop refetch).
  const fetchRef = useRef(fetchOptions)
  fetchRef.current = fetchOptions
  const rootRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const activeRef = useRef<HTMLLIElement>(null)

  // Nilai di-reset dari luar (form dibersihkan) → kosongkan label tampilan.
  useEffect(() => {
    if (!value) setLabel('')
  }, [value])

  // Ambil opsi saat terbuka / kata kunci berubah; didebounce, permintaan lama dibatalkan.
  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    const run = async () => {
      try {
        const opts = await fetchRef.current(term.trim())
        if (!cancelled) {
          setOptions(opts)
          setActive(opts.length ? 0 : -1)
        }
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
  }, [open, term, debounceMs])

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
    inputRef.current?.focus()
  }

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (!open) setOpen(true)
      else setActive((i) => Math.min(i + 1, options.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      if (open && active >= 0 && options[active]) {
        e.preventDefault()
        choose(options[active])
      }
    } else if (e.key === 'Escape') {
      if (open) {
        e.preventDefault()
        setOpen(false)
      }
    }
  }

  return (
    <div className={`combobox${disabled ? ' is-disabled' : ''}`} ref={rootRef}>
      <div className="cb-field">
        <IconSearch size={16} />
        <input
          ref={inputRef}
          type="text"
          role="combobox"
          aria-expanded={open}
          autoComplete="off"
          disabled={disabled}
          value={open ? term : label}
          placeholder={label || placeholder}
          onChange={(e) => {
            setTerm(e.target.value)
            if (!open) setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
        />
        {loading && <Spinner />}
        {value && !loading && (
          <button
            type="button"
            className="ghost icon-btn"
            onMouseDown={(e) => e.preventDefault()}
            onClick={clear}
            aria-label="Hapus pilihan"
          >
            <IconClose size={15} />
          </button>
        )}
      </div>

      {open && (
        // preventDefault pada mousedown menu → klik opsi tak mem-blur input duluan.
        <ul className="cb-menu" onMouseDown={(e) => e.preventDefault()}>
          {loading && options.length === 0 ? (
            <li className="cb-note">Memuat…</li>
          ) : options.length === 0 ? (
            <li className="cb-note">{emptyText}</li>
          ) : (
            options.map((item, i) => {
              const id = toId(item)
              const meta = toMeta?.(item)
              // Header grup saat labelnya berubah dari item sebelumnya (opsi diasumsikan terurut per grup).
              const group = groupOf?.(item)
              const showGroup = group !== undefined && (i === 0 || groupOf?.(options[i - 1]) !== group)
              return (
                <Fragment key={id}>
                  {showGroup && <li className="cb-group">{group}</li>}
                  <li
                    ref={i === active ? activeRef : undefined}
                    className={`cb-option${i === active ? ' active' : ''}${id === value ? ' selected' : ''}`}
                    onMouseEnter={() => setActive(i)}
                    onClick={() => choose(item)}
                  >
                    <span className="cb-label">{toLabel(item)}</span>
                    {meta && <span className="cb-meta">{meta}</span>}
                  </li>
                </Fragment>
              )
            })
          )}
        </ul>
      )}
    </div>
  )
}
