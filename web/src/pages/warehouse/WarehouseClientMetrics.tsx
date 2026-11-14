import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { getClientOperationsOverview } from '@/api/warehouse/clientOperations'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'

export function WarehouseClientMetrics() {
  const result = useWarehouseQuery(useCallback(() => getClientOperationsOverview(), []))
  return <WarehouseState {...result}>{data => <div className="operational-metrics">{[
    ['Pembelian belum lengkap', data.purchasesPending, 'purchases'], ['Pinjaman menunggu Admin', data.loansAwaitingAdmin, 'loans'], ['Pinjaman menunggu Manager', data.loansAwaitingManager, 'loans'], ['Pinjaman masih aktif', data.activeLoans, 'loans'], ['Klaim garansi terbuka', data.openWarranties, 'warranties'],
  ].map(([label, count, path]) => count !== null && <Link key={String(label)} className="card stack" to={'/warehouse/' + path}><span>{label}</span><strong>{count}</strong></Link>)}</div>}</WarehouseState>
}
