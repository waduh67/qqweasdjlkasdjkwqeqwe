import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { acceptReplenishment, archiveReplenishmentRule, cancelReplenishment, getReplenishment, recomputeReplenishment, replenishmentHistory, type ReplenishmentDetails } from '@/api/warehouse/replenishment'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseReplenishmentRuleForm } from './WarehouseReplenishmentRuleForm'
import { WarehouseReplenishmentReceipt } from './WarehouseReplenishmentReceipt'
import { replenishmentLabels } from './replenishmentPresentation'
import { receiptLink } from './receiptFiles'

export function WarehouseReplenishmentDetail({ kind, id }: { kind: 'rules' | 'requests'; id: string }) {
  const loader = useCallback(() => getReplenishment(kind, id), [kind, id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{data => <ReplenishmentBody data={data} reload={result.reload} />}</WarehouseState>
}
function ReplenishmentBody({ data, reload }: { data: ReplenishmentDetails; reload: () => void }) {
  const { can } = useCan(), [edit, setEdit] = useState(false), [receiving, setReceiving] = useState(false), [history, setHistory] = useState(false)
  const [operation, setOperation] = useState<{ action: string; description: string; command: WarehouseCommand<unknown> } | null>(null)
  const { rule, request, position } = data
  const manage = can('inventory.request.manage'), eligible = rule.active && data.sku.state === 'ACTIVE' && data.location.replenishmentEligible
  const pending = request?.state === 'PENDING'
  const fresh = request && request.ruleRevision === rule.revision && request.quantityBase === data.suggestedQuantityBase && BigInt(data.suggestedQuantityBase) > 0n
  const q = (value: string) => <WarehouseQuantity value={value} unit={rule.baseUnit} />
  return <>
    <section className="card stack" aria-label="Detail pengisian stok"><h2>{data.sku.name} · {data.sku.code}</h2><p>{data.location.name ?? data.location.code} · {rule.active ? 'Aturan aktif' : 'Aturan diarsipkan'} · Revisi {rule.revision}</p>
      <p>Minimum {q(rule.minimumBase)} · Target {q(rule.targetBase)} · Maksimum {q(rule.maximumBase)} · Kelipatan {q(rule.packageMultipleBase)} · Waktu tunggu {rule.leadTimeDays} hari.</p>
      <h3>Posisi saat ini</h3><p>Tersedia {q(position.availableBase)} · Dipesan / disiapkan {q(position.reservedBase)} · Barang masuk terkonfirmasi {q(position.confirmedInboundBase)}</p>
      <p>Saran pengisian saat ini: <strong>{q(data.suggestedQuantityBase)}</strong>. Saran tidak menambah stok dan belum menjadi pesanan pembelian.</p>
      {!eligible && <p>Aturan, barang, atau lokasi tidak aktif untuk pengisian.</p>}
      <div className="row wrap"><Button onClick={reload}>Muat ulang pengisian</Button>{manage && <>
        <Button disabled={data.sku.state !== 'ACTIVE' || !data.location.replenishmentEligible} onClick={() => setEdit(true)}>Ubah aturan</Button>
        <Button disabled={!eligible} onClick={() => setOperation({ action: 'Hitung ulang saran', command: recomputeReplenishment(rule), description: 'Periksa stok dan barang masuk terbaru. Saran yang belum dikonfirmasi dapat berubah atau selesai jika kebutuhan telah terpenuhi.' })}>Hitung ulang saran</Button>
        <Button disabled={!rule.active || Boolean(pending && request?.acceptedAt)} onClick={() => setOperation({ action: 'Arsipkan aturan', command: archiveReplenishmentRule(rule), description: 'Aturan dinonaktifkan. Saran tertunda yang belum dikonfirmasi dibatalkan; riwayat tetap tersimpan.' })}>Arsipkan aturan</Button>
      </>}</div>
      {pending && request?.acceptedAt && <p>Batalkan permintaan yang sudah dikonfirmasi sebelum mengarsipkan aturan.</p>}
    </section>
    {request ? <section className="card stack" aria-label="Saran pengisian tercatat"><h3>{replenishmentLabels[request.state]}{request.acceptedAt ? ' · Sudah dikonfirmasi' : ''}</h3>
      <p>Permintaan revisi {request.revision} · Aturan saat dihitung: revisi {request.ruleRevision} · <WarehouseTime value={request.createdAt} /></p>
      <p>Jumlah tercatat: <strong>{q(request.quantityBase)}</strong>. Tersedia saat dihitung {q(request.availableBase)}; masuk terkonfirmasi {q(request.confirmedInboundBase)}.</p>
      {request.acceptedAt && <p>Dikonfirmasi <WarehouseTime value={request.acceptedAt} />. Stok tersedia tetap mengikuti penerimaan fisik.</p>}
      {pending && !request.acceptedAt && !fresh && <p role="alert">Saran tersimpan berbeda dari kebutuhan atau versi aturan saat ini. Hitung ulang sebelum mengonfirmasi.</p>}
      <div className="row wrap">{manage && pending && <>
        {!request.acceptedAt && <Button variant="primary" disabled={!eligible || !fresh} onClick={() => setOperation({ action: 'Konfirmasi kebutuhan pengisian', command: acceptReplenishment(request, rule), description: 'Simpan konfirmasi kebutuhan sejumlah yang tercatat. Barang belum diterima; tindakan ini tidak menambah stok atau membuat pembelian otomatis.' })}>Konfirmasi kebutuhan</Button>}
        <Button onClick={() => setOperation({ action: 'Batalkan permintaan pengisian', command: cancelReplenishment(request), description: 'Batalkan permintaan ini. Pembatalan tidak membatalkan penerimaan fisik atau menghapus barang yang sudah masuk.' })}>Batalkan permintaan</Button>
        {request.acceptedAt && !request.receivingDocumentId && can('inventory.receipt.view') && <Button onClick={() => setReceiving(true)}>Hubungkan penerimaan</Button>}
      </>}{request.receivingDocumentId && can('inventory.receipt.view') && <Link to={receiptLink(request.receivingDocumentId)}>Buka penerimaan terkait</Link>}</div>
      {receiving && request.acceptedAt && pending && manage && can('inventory.receipt.view') && <WarehouseReplenishmentReceipt data={data} request={request} reload={reload} onClose={() => setReceiving(false)} />}
    </section> : <p className="card">Belum ada saran tertunda. {manage ? 'Hitung ulang untuk mencatat kebutuhan saat ini.' : 'Petugas pengelola dapat menghitung ulang kebutuhan.'}</p>}
    {edit && manage && <WarehouseReplenishmentRuleForm data={data} onClose={() => setEdit(false)} onDone={reload} reload={reload} />}
    <section className="card stack"><Button onClick={() => setHistory(value => !value)}>{history ? 'Tutup riwayat pengisian' : 'Lihat riwayat pengisian'}</Button>{history && <ReplenishmentHistory id={rule.id} />}</section>
    {operation && <WarehouseCommandDialog title={operation.action} confirmLabel="Konfirmasi tindakan" command={operation.command} onClose={() => setOperation(null)} onDone={reload} onReload={reload}
      summary={<><p>{data.sku.name} · {data.location.name ?? data.location.code}</p><p>Aturan revisi {rule.revision}{request && ` · Permintaan revisi ${request.revision}`}</p>{request && <p>Jumlah permintaan {q(request.quantityBase)}</p>}<p>{operation.description}</p></>} />}
  </>
}
function ReplenishmentHistory({ id }: { id: string }) {
  const [page, setPage] = useState(0), loader = useCallback(() => replenishmentHistory(id, page), [id, page]), result = useWarehouseQuery(loader)
  const actions: Record<string, string> = { 'rule.create': 'Aturan dibuat', 'rule.update': 'Aturan diperbarui', 'rule.archive': 'Aturan diarsipkan', 'rule.recompute': 'Kebutuhan dihitung ulang', 'request.accept': 'Kebutuhan dikonfirmasi', 'request.cancel': 'Permintaan dibatalkan', 'request.receiving': 'Penerimaan dihubungkan' }
  return <WarehouseState {...result}>{rows => <>{rows.length ? <ul>{rows.map(row => <li key={row.id}>{actions[row.action] ?? row.action} · Revisi {row.revision} · <WarehouseTime value={row.createdAt} /></li>)}</ul> : <p>Tidak ada catatan pada halaman ini.</p>}
    <div className="row wrap"><Button disabled={page === 0} onClick={() => setPage(page - 1)}>Riwayat sebelumnya</Button><span>Halaman {page + 1}</span><Button disabled={rows.length < 25} onClick={() => setPage(page + 1)}>Riwayat berikutnya</Button></div>
  </>}</WarehouseState>
}
