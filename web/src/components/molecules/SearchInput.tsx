import { IconClose, IconSearch } from '@/components/atoms/icons'

/** Kotak pencarian teks-bebas dengan ikon kaca pembesar + tombol bersihkan. */
export function SearchInput({
  value,
  onChange,
  placeholder = 'Cari…',
}: {
  value: string
  onChange: (value: string) => void
  placeholder?: string
}) {
  return (
    <div className="search-input">
      <IconSearch size={16} />
      <input
        type="search"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
      {value && (
        <button className="ghost icon-btn" onClick={() => onChange('')} aria-label="Bersihkan pencarian">
          <IconClose size={15} />
        </button>
      )}
    </div>
  )
}
