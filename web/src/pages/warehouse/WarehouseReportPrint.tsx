import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal, flushSync } from 'react-dom'
import { getReportPrint } from '@/api/warehouse/reports'
import type { ReportPrint } from '@/api/warehouse/reportModels'
import { warehouseError } from '@/api/warehouse/errors'
import { Button } from '@/components/atoms'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseCost } from './WarehouseStockDetail'

export function WarehouseReportPrint({ id, revision, onClose }: { id: string; revision: number; onClose: () => void }) {
  const loader = useCallback(() => getReportPrint(id, revision), [id, revision])
  const result = useWarehouseQuery(loader)
  return <section className="card stack" aria-label="Pratinjau cetak dokumen"><Button onClick={onClose}>Tutup pratinjau</Button><WarehouseState {...result}>{data => <PrintActions data={data} reload={result.reload} />}</WarehouseState></section>
}
function PrintActions({ data, reload }: { data: ReportPrint; reload: () => void }) {
  const [current, setCurrent] = useState(data), [printable, setPrintable] = useState<ReportPrint | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  async function print() {
    setBusy(true); setError(null)
    try {
      const fresh = await getReportPrint(data.documentId, data.documentRevision)
      if (!mounted.current) return
      flushSync(() => { setCurrent(fresh); setPrintable(fresh) }); window.print()
    } catch (caught) { if (mounted.current) setError(warehouseError(caught)) }
    finally { if (mounted.current) { setBusy(false); setPrintable(null) } }
  }
  if (error) return <div role="alert"><p>{error}</p><Button onClick={reload}>Muat ulang dokumen</Button></div>
  return <><PrintContent data={current} /><p>Cetakan mencatat kejadian pada revisi ini. Izin dan isi dokumen diperiksa ulang sebelum mencetak.</p><Button disabled={busy} onClick={() => void print()}>{busy ? 'Menyiapkan cetakan…' : 'Cetak dokumen'}</Button>
    {printable && createPortal(<div className="warehouse-issue-print"><PrintContent data={printable} /></div>, document.body)}</>
}
function PrintContent({ data }: { data: ReportPrint }) {
  return <div className="stack" style={{ overflowWrap: 'anywhere' }}><h2>{data.documentCode}</h2>
    <p>{({ RECEIPT: 'Penerimaan', ISSUE: 'Pengeluaran', RETURN: 'Pengembalian' })[data.kind]} · Revisi {data.documentRevision} · {data.state}</p>
    <p><WarehouseTime value={data.recordedAt} /></p>{data.supplier?.name && <p>Pemasok: {data.supplier.name}</p>}{data.workOrderId && <p>WO: {data.workOrderCode ?? data.workOrderId}</p>}
    <ol>{data.lines.map(line => <li key={line.lineId}><strong>{line.skuName ?? 'Nama barang tidak tercatat pada dokumen'}</strong> · {line.skuCode}<br />{line.serial && <>{line.serial}<br /></>}
      <WarehouseQuantity value={line.quantity.quantityBase} unit={line.quantity.baseUnit} /><WarehouseCost cost={line.cost} unit={line.quantity.baseUnit} />
    </li>)}</ol><p>Referensi pembukuan: {data.operationId}</p>
  </div>
}
