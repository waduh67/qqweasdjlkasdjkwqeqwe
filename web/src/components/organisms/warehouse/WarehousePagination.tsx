import { Button } from '@/components/atoms'

export function WarehousePagination({ page, size, total, onChange }: { page: number; size: number; total: number; onChange: (page: number) => void }) {
  if (total <= size && page === 0) return null
  return <nav className="spread wrap" aria-label="Halaman data"><span>{total} entri · Halaman {page + 1}</span><div className="row">
    <Button disabled={page === 0} onClick={() => onChange(page - 1)}>Sebelumnya</Button><Button disabled={(page + 1) * size >= total} onClick={() => onChange(page + 1)}>Berikutnya</Button>
  </div></nav>
}
