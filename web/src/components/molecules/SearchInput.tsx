import { Input, makeStyles } from '@fluentui/react-components'
import { Button } from '@/components/atoms/Button'
import { IconClose, IconSearch } from '@/components/atoms/icons'

/**
 * Kotak pencarian teks-bebas dengan ikon kaca pembesar + tombol bersihkan.
 *
 * Dibangun di atas Fluent `Input` (`contentBefore` ikon cari, `contentAfter` tombol
 * bersihkan) — gaya kotak/fokus datang dari TEMA Fluent, bukan CSS `.search-input`
 * di index.css. Ukuran melar (tumbuh dalam bilah alat) dipindah ke `makeStyles`.
 */
const useStyles = makeStyles({
  root: {
    flexGrow: 1,
    flexShrink: 1,
    flexBasis: '16rem',
    minWidth: '14rem',
    maxWidth: '24rem',
  },
})

export function SearchInput({
  value,
  onChange,
  placeholder = 'Cari…',
}: {
  value: string
  onChange: (value: string) => void
  placeholder?: string
}) {
  const s = useStyles()
  return (
    <Input
      className={s.root}
      type="search"
      value={value}
      onChange={(_, data) => onChange(data.value)}
      placeholder={placeholder}
      contentBefore={<IconSearch size={16} />}
      contentAfter={
        value ? (
          <Button
            variant="subtle"
            size="small"
            icon={<IconClose size={15} />}
            onClick={() => onChange('')}
            aria-label="Bersihkan pencarian"
          />
        ) : undefined
      }
    />
  )
}
