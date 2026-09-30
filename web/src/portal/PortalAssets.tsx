import { useCallback, useState } from 'react'
import { Button } from '@/components/atoms'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { usePortalAuth } from './PortalAuthContext'
import { getPortalAssets } from './portalAssets'

export function PortalAssets() {
  const { customer } = usePortalAuth()
  return customer ? <Assets key={`${customer.tenantId}:${customer.customerId}`} /> : null
}
function Assets() {
  const [page, setPage] = useState(0), load = useCallback(() => getPortalAssets(page), [page]), { state, reload } = useWarehouseQuery(load)
  return <section className="card stack" aria-label="Kepemilikan perangkat"><h2>Perangkat dan kepemilikan</h2>
    {state.status === 'loading' && <p role="status">Memuat riwayat perangkat…</p>}
    {state.status === 'error' && <div role="alert"><p>Riwayat perangkat belum dapat dimuat.</p><Button onClick={reload}>Coba lagi</Button></div>}
    {state.status === 'ready' && <>{state.data.items.length === 0 && <p>Belum ada riwayat pemasangan perangkat yang terverifikasi.</p>}
      {state.data.items.map(row => <article key={`${row.serialNumber}:${row.installedAt}`} className="stack"><h3>{row.deviceLabel} · {row.serialNumber}</h3>
        <p>{row.ownershipMode === 'LOAN' ? 'Pinjam pakai' : 'Perangkat jual'} · {row.legalOwner === 'CUSTOMER' ? 'Milik Anda' : 'Milik ISP'} · {row.removedAt ? 'Sudah dilepas' : 'Terpasang'}.</p>
        {row.legalOwner === 'ISP' && row.ownershipMode === 'LOAN' && !row.removedAt && <p>Perangkat perlu dikembalikan saat layanan berakhir. Hubungi dukungan untuk pengembaliannya.</p>}
        {row.legalOwner === 'ISP' && row.ownershipMode === 'SALE' && <p>Serah-terima kepemilikan belum selesai. Hubungi dukungan untuk memeriksanya.</p>}
        <p>Dipasang {new Date(row.installedAt).toLocaleDateString('id-ID')}{row.removedAt ? ` · Dilepas ${new Date(row.removedAt).toLocaleDateString('id-ID')}` : ''}.</p>
      </article>)}
      {(page > 0 || state.data.totalElements > state.data.size) && <nav className="row wrap" aria-label="Halaman riwayat perangkat"><Button disabled={page === 0} onClick={() => setPage(page - 1)}>Sebelumnya</Button><span>Halaman {page + 1}</span><Button disabled={(page + 1) * state.data.size >= state.data.totalElements} onClick={() => setPage(page + 1)}>Berikutnya</Button></nav>}
    </>}
  </section>
}
