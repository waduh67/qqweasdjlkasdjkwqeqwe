import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/atoms'

export function Pagination({ page, size, total, busy, onChange }: { page: number; size: number; total: number; busy?: boolean; onChange: (page: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size))
  return <nav className="pagination" aria-label="Halaman data">
    <span className="muted">{total.toLocaleString('id-ID')} hasil{total > 0 && ` · ${page * size + 1}–${Math.min((page + 1) * size, total)}`}</span>
    {pages > 1 && <div className="row"><Button disabled={busy || page === 0} onClick={() => onChange(page - 1)} icon={<ChevronLeft size={16} />}>Sebelumnya</Button><span>{page + 1} / {pages}</span><Button disabled={busy || page + 1 >= pages} onClick={() => onChange(page + 1)} icon={<ChevronRight size={16} />}>Berikutnya</Button></div>}
  </nav>
}
