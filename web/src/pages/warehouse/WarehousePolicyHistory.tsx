import { useCallback, useState } from 'react'
import { listPolicyHistory } from '@/api/warehouse/settings'
import { Button } from '@/components/atoms'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { policyRulesDraft } from './policyDraft'
import { WarehousePolicyPreview } from './WarehousePolicyPreview'

export function WarehousePolicyHistory() {
  const [open, setOpen] = useState(false)
  return <section className="card stack" aria-label="Riwayat kebijakan"><Button onClick={() => setOpen(value => !value)}>{open ? 'Tutup riwayat kebijakan' : 'Lihat riwayat kebijakan'}</Button>{open && <History />}</section>
}
function History() {
  const [page, setPage] = useState(0), loader = useCallback(() => listPolicyHistory(page), [page]), result = useWarehouseQuery(loader)
  return <><p>Versi tersimpan yang seluruh lokasi kebijakannya dapat Anda akses saat ini. Nama pemeriksa mengikuti direktori saat ini.</p><WarehouseState {...result}>{data => <>
    {!data.items.length && <p>Belum ada versi kebijakan dalam cakupan ini.</p>}
    {data.items.map(row => row.current && <details key={row.current.id} className="card"><summary>Versi {row.current.revision} · <WarehouseTime value={row.current.createdAt} /></summary>
      <p>Disimpan oleh {row.references.users.find(user => user.id === row.current!.actorId)?.name ?? 'pengguna yang namanya tidak tersedia'}.</p>
      <WarehousePolicyPreview locations={row.references.locations} rules={policyRulesDraft(row)} currency={row.current.currency} expiry={String(row.current.expiryHours)} />
    </details>)}<WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></>
}
