import type { ReactNode } from 'react'
import type { WarehouseResult } from '@/hooks/useWarehouseQuery'
import { warehouseError } from '@/api/warehouse/errors'
import { Button, EmptyState, Spinner } from '@/components/atoms'

export function WarehouseState<T>({ state, reload, children }: { state: WarehouseResult<T>; reload: () => void; children: (data: T) => ReactNode }) {
  if (state.status === 'loading') return <div className="card row" role="status"><Spinner /> Memuat data gudang…</div>
  if (state.status === 'error') return <div className="card stack" role="alert"><strong>Data belum berhasil dimuat</strong><p>{warehouseError(state.error)}</p><Button onClick={reload}>Coba lagi</Button></div>
  return children(state.data)
}

export function WarehouseDenied() {
  return <div className="card" role="alert"><EmptyState title="Akses gudang dibatasi" hint="Minta pengelola akses memberikan izin untuk halaman ini dan cakupan gudang yang sesuai." /></div>
}
