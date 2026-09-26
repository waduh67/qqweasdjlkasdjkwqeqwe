import { useCallback, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Checkbox } from '@fluentui/react-components'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { getReceipt } from '@/api/warehouse/receipts'
import { listReplacements, requestReplacement, type ReturnDetails, type SupplierReplacement } from '@/api/warehouse/returns'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { locationLabel, receiptLocations } from './receiptChoices'
import { returnItemLabel } from './returnPresentation'
import { buildReplacement } from './replacementDraft'

export function WarehouseSupplierReplacements({ details, reload }: { details: ReturnDetails; reload: () => void }) {
  const { can } = useCan(), id = details.returnCase.id, [page, setPage] = useState(0), [creating, setCreating] = useState(false)
  const loader = useCallback(() => listReplacements(id, page), [id, page]), result = useWarehouseQuery(loader)
  const canCreate = can('inventory.return.manage') && can('inventory.receipt.manage') && can('inventory.location.view')
  if (creating) return <ReplacementEditor details={details} onClose={() => setCreating(false)} onDone={reload} />
  return <section className="card stack" aria-label="Penerimaan perangkat pengganti"><h2>Perangkat pengganti dari penyedia</h2>
    <p>Serial berbeda menjadi penerimaan baru dengan asal dan kepemilikan yang terikat ke kasus servis. Perangkat lama tetap tercatat di penyedia sampai ada penanganan terpisah.</p>
    <WarehouseState {...result}>{items => <>
      {items.length ? items.map(item => <ReplacementReceipt key={item.id} item={item} />) : <p>Belum ada penerimaan pengganti pada halaman ini.</p>}
      <div className="row wrap"><Button disabled={page === 0} onClick={() => setPage(page - 1)}>Pengganti sebelumnya</Button>
        <span>Halaman {page + 1}</span><Button disabled={items.length < 25} onClick={() => setPage(page + 1)}>Pengganti berikutnya</Button></div>
      {details.returnCase.state === 'REPAIR' && details.returnCase.repair?.returnedRevision === null && <>
        <Button disabled={!canCreate} onClick={() => setCreating(true)}>Siapkan penerimaan pengganti</Button>
        {!canCreate && <p className="muted">Pembuatan penerimaan pengganti memerlukan izin kelola retur, kelola penerimaan, dan lihat lokasi.</p>}
        {items.length > 0 && <p className="muted">Periksa usulan yang sudah tercatat sebelum membuat lagi. Satu kasus servis hanya dapat menerima satu perangkat pengganti.</p>}
      </>}
    </>}</WarehouseState>
  </section>
}
function ReplacementReceipt({ item }: { item: SupplierReplacement }) {
  const loader = useCallback(() => getReceipt(item.receiptId), [item.receiptId]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{receipt => <section className="stack"><h3>{receipt.externalReference}</h3>
    <p>{receipt.supplierName} · <WarehouseStatus status={receipt.state} /> · <WarehouseStatus status={item.legalOwner} /></p>
    {receipt.lines.map(line => <p key={line.id}>{line.skuName} · {line.serial ?? 'Serial belum tercatat'}</p>)}
    <p>{item.replacementAssetId ? 'Identitas pengganti telah terbentuk; ikuti pemeriksaan dan lokasi pada penerimaannya.' : 'Usulan pengganti sudah tercatat. Belum ada perangkat pengganti yang diterima.'}</p>
    <Link to={`/warehouse/receipts?receiptId=${encodeURIComponent(item.receiptId)}`}>Buka penerimaan {receipt.externalReference}</Link>
  </section>}</WarehouseState>
}
function ReplacementEditor({ details, onClose, onDone }: { details: ReturnDetails; onClose: () => void; onDone: () => void }) {
  const { can } = useCan(), [source, setSource] = useState<WarehouseLocation | null>(null), [quarantine, setQuarantine] = useState<WarehouseLocation | null>(null)
  const [serial, setSerial] = useState(''), [mac, setMac] = useState(''), [reference, setReference] = useState(''), [evidence, setEvidence] = useState('')
  const [useCost, setUseCost] = useState(false), [totalMinor, setTotalMinor] = useState(''), [currency, setCurrency] = useState('IDR')
  const [error, setError] = useState(''), [operation, setOperation] = useState<WarehouseCommand<SupplierReplacement> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      setOperation(requestReplacement(details.returnCase.id, buildReplacement(details, source, quarantine, serial, mac, reference, evidence,
        useCost && can('inventory.cost.view') ? { totalMinor, currency } : null)))
      setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa perangkat pengganti.') }
  }
  return <><form className="card stack" aria-label="Penerimaan perangkat pengganti" onSubmit={prepare}>
    <h2>Siapkan penerimaan pengganti</h2><p>{details.references.code} · {returnItemLabel(details.references.item)} · {details.references.vendor?.name}</p>
    <p>Satu unit dari SKU yang sama, dengan serial baru. Draft ini belum menerima barang atau menghapus perangkat lama.</p>
    {details.returnCase.legalOwner === 'CUSTOMER' && <p role="status">Pengganti tetap milik pelanggan. Penerimaan dan inspeksi tidak memasukkannya ke stok tersedia ISP.</p>}
    <WarehousePicker label="Batas penerimaan pengganti" load={receiptLocations} value={source} onChange={setSource} name={locationLabel} eligible={row => row.kind === 'TRANSIT' && row.code === 'RECEIPT_SOURCE' && !row.issueEligible} />
    <WarehousePicker label="Karantina perangkat pengganti" load={receiptLocations} value={quarantine} onChange={setQuarantine} name={locationLabel} eligible={row => row.kind === 'QUARANTINE' && !row.issueEligible} />
    <TextField label="Serial perangkat pengganti" value={serial} required maxLength={128} onChange={(_, data) => setSerial(data.value)}
      onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation() } }} />
    <TextField label="MAC perangkat pengganti (opsional)" value={mac} maxLength={32} onChange={(_, data) => setMac(data.value)} />
    <TextField label="Referensi surat pengganti" value={reference} required maxLength={500} onChange={(_, data) => setReference(data.value)} />
    <TextField label="Referensi bukti pengganti" value={evidence} required maxLength={500} onChange={(_, data) => setEvidence(data.value)} />
    {can('inventory.cost.view') && <><Checkbox label="Nilai pengganti diketahui" checked={useCost} onChange={(_, data) => setUseCost(data.checked === true)} />
      {useCost ? <><WarehouseQuantityField label="Nilai total satuan terkecil mata uang" value={totalMinor} unit="EA" allowZero onChange={setTotalMinor} />
        <TextField label="Mata uang pengganti" value={currency} maxLength={3} onChange={(_, data) => setCurrency(data.value)} /></> : <p className="muted">Nilai belum diketahui; tidak dianggap nol.</p>}</>}
    {error && <p role="alert" className="error">{error}</p>}<div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button variant="primary" type="submit">Tinjau usulan pengganti</Button></div>
  </form>
    {operation && <WarehouseCommandDialog title="Konfirmasi penerimaan pengganti" confirmLabel="Catat usulan pengganti" command={operation} onDone={onDone} onReload={onDone} onClose={() => setOperation(null)}
      summary={<><p>{reference} · {details.references.vendor?.name}</p><p>{details.references.item.name} · 1 unit · Serial baru: {serial}{mac && ` · MAC ${mac}`}</p>
        <p>{source && locationLabel(source)} → {quarantine && locationLabel(quarantine)}</p><p>Bukti: {evidence}</p>
        <p>Perangkat lama tetap tercatat. Usulan ini disimpan sebagai catatan tetap; lanjutkan persetujuan yang berlaku, penerimaan fisik, dan inspeksi dari detail penerimaan.</p><p><WarehouseStatus status={details.returnCase.legalOwner} /></p>
        {can('inventory.cost.view') && <p>{useCost ? `Nilai: ${totalMinor} ${currency.toUpperCase()} (satuan terkecil).` : 'Nilai belum diketahui.'}</p>}</>} />}
  </>
}
