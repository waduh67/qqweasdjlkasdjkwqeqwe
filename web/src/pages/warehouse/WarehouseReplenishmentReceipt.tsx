import { useCallback, useState } from 'react'
import { getReceipt, listReceipts, type WarehouseReceipt } from '@/api/warehouse/receipts'
import { bindReplenishmentReceipt, type ReplenishmentDetails, type ReplenishmentRequest } from '@/api/warehouse/replenishment'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, SelectField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function WarehouseReplenishmentReceipt({ data, request, reload, onClose }: { data: ReplenishmentDetails; request: ReplenishmentRequest; reload: () => void; onClose: () => void }) {
  const [receipt, setReceipt] = useState<WarehouseReceipt | null>(null)
  const [state, setState] = useState<'RECEIVED_IN_INSPECTION' | 'PUTAWAY'>('RECEIVED_IN_INSPECTION')
  const load = useCallback((_search: string, page: number) => listReceipts({ skuId: data.rule.skuId, status: state, page }), [data.rule.skuId, state])
  return <section className="card stack" aria-label="Penerimaan pengisian"><h3>Hubungkan penerimaan barang</h3>
    <p>Pilih baris penerimaan yang benar-benar tercatat untuk jumlah yang disetujui. Pemeriksaan dan penempatan barang tetap dilakukan melalui penerimaan.</p>
    <SelectField label="Status penerimaan pengisian" value={state} onChange={(_, value) => { setReceipt(null); setState(value.value as typeof state) }}><option value="RECEIVED_IN_INSPECTION">Dalam pemeriksaan</option><option value="PUTAWAY">Sudah ditempatkan</option></SelectField>
    <WarehousePicker label="Dokumen penerimaan pengisian" load={load} value={receipt} onChange={setReceipt} name={row => `${row.externalReference} · ${row.supplierName}`} searchable={false} />
    {receipt && <ReceiptLines key={receipt.id} id={receipt.id} data={data} request={request} reload={reload} />}
    <Button onClick={onClose}>Tutup pilihan penerimaan</Button>
  </section>
}
function ReceiptLines({ id, data, request, reload }: { id: string; data: ReplenishmentDetails; request: ReplenishmentRequest; reload: () => void }) {
  const loader = useCallback(() => getReceipt(id), [id]), result = useWarehouseQuery(loader)
  const [lineId, setLineId] = useState(''), [operation, setOperation] = useState<WarehouseCommand<ReplenishmentRequest> | null>(null)
  return <WarehouseState {...result}>{receipt => {
    const lines = ['RECEIVED_IN_INSPECTION', 'PUTAWAY'].includes(receipt.state) ? receipt.lines.filter(line => line.skuId === data.rule.skuId && line.baseUnit === request.baseUnit && line.quantityBase === request.quantityBase) : []
    return <div className="stack"><p>Dokumen revisi {receipt.revision} · Dibutuhkan <WarehouseQuantity value={request.quantityBase} unit={request.baseUnit} /></p>
      <SelectField label="Baris penerimaan pengisian" value={lineId} onChange={(_, value) => setLineId(value.value)}><option value="">Pilih baris dengan jumlah yang cocok</option>{lines.map(line => <option value={line.id} key={line.id}>{line.skuName} · {line.serial ?? line.lotCode ?? `Baris ${line.inputLineNumber}`}</option>)}</SelectField>
      {!lines.length && <p>Dokumen ini tidak memiliki baris dengan barang, satuan, dan jumlah persis sesuai permintaan.</p>}
      <Button disabled={!lines.some(line => line.id === lineId)} onClick={() => setOperation(bindReplenishmentReceipt(request, receipt.id, receipt.revision, lineId))}>Tinjau hubungan penerimaan</Button>
      {operation && <WarehouseCommandDialog title="Konfirmasi penerimaan pengisian" confirmLabel="Hubungkan penerimaan" command={operation} onClose={() => setOperation(null)} onDone={reload} onReload={reload}
        summary={<><p>{receipt.externalReference} · Revisi {receipt.revision} · {lines.find(line => line.id === lineId)?.skuName}</p><WarehouseQuantity value={request.quantityBase} unit={request.baseUnit} /><p>Permintaan revisi {request.revision}. Hubungan ini mencatat sumber barang masuk; stok tersedia mengikuti hasil pemeriksaan dan penempatan.</p></>} />}
    </div>
  }}</WarehouseState>
}
