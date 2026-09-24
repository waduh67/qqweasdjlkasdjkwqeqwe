import { useCallback, useState, type FormEvent } from 'react'
import { getLocation } from '@/api/warehouse/masters'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { listReturnSources, receiveReturn, type ReturnSource, type WarehouseReturn } from '@/api/warehouse/returns'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { buildReturnIntake } from './returnDraft'
import { locationLabel, receiptLocations } from './receiptChoices'
import { returnItemLabel, returnOriginLabels, returnSourceLabel } from './returnPresentation'

type SourceChoice = ReturnSource & { id: string }
const sources = async (search: string, page: number) => {
  const result = await listReturnSources({ query: search.trim() || undefined, page })
  return { ...result, items: result.items.map(row => ({ ...row, id: row.sourceDocumentId })) }
}
type Props = { onSaved: (row: WarehouseReturn) => void; onClose: () => void; onReload: () => void }
export function WarehouseReturnEditor(props: Props) {
  const { can } = useCan(), [source, setSource] = useState<SourceChoice | null>(null)
  if (!can('inventory.return.manage')) return <WarehouseDenied />
  if (!can('inventory.location.view')) return <div className="card stack" role="alert"><p>Izin lihat lokasi diperlukan untuk memilih karantina penerimaan.</p><Button onClick={props.onClose}>Kembali</Button></div>
  return <div className="card stack"><h2>Terima retur</h2>
    <p>Pilih sisa material yang sudah diterima gudang atau perangkat hasil pelepasan yang diserahkan petugas lain. Sumber yang sudah menjadi retur tidak ditawarkan lagi.</p>
    <WarehousePicker label="Sumber retur" load={sources} value={source} name={returnSourceLabel} onChange={setSource} />
    {source ? <SelectedSource key={source.id} source={source} {...props} /> : <Button onClick={props.onClose}>Batal</Button>}
  </div>
}
function SelectedSource({ source, ...props }: Props & { source: ReturnSource }) {
  const loader = useCallback(() => source.quarantineLocationId ? getLocation(source.quarantineLocationId) : Promise.resolve(null), [source.quarantineLocationId])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{location => <IntakeForm source={source} initialLocation={location} {...props} />}</WarehouseState>
}
function IntakeForm({ source, initialLocation, onSaved, onClose, onReload }: Props & { source: ReturnSource; initialLocation: WarehouseLocation | null }) {
  const [destination, setDestination] = useState(initialLocation), [evidence, setEvidence] = useState(''), [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseReturn> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try { setOperation(receiveReturn(buildReturnIntake(source, destination, evidence))); setError(null) }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa sumber retur.') }
  }
  return <><form className="stack" aria-label="Penerimaan retur" onSubmit={prepare}>
    <p>{returnOriginLabels[source.origin]} · {source.code}</p><p><strong>{returnItemLabel(source.item)}</strong> · <WarehouseQuantity value={source.quantityBase} unit={source.baseUnit} /> · <WarehouseStatus status={source.legalOwner} /></p>
    {source.origin === 'MATERIAL_RESIDUAL' ? <p>Sudah diterima di {locationLabel(source.location)}. Pencatatan retur membuka pemeriksaan tanpa menambah stok lagi.</p>
      : <><p>Diterima dari {locationLabel(source.location)}. Perangkat masuk karantina dan masih harus diperiksa.</p>
        <WarehousePicker label="Karantina penerimaan retur" load={receiptLocations} value={destination} name={locationLabel} eligible={row => row.kind === 'QUARANTINE' && !row.issueEligible} onChange={setDestination} /></>}
    {source.legalOwner === 'CUSTOMER' && <p role="status">Perangkat tetap milik pelanggan dan tidak boleh menjadi stok tersedia ISP.</p>}
    <TextField label="Referensi bukti penerimaan retur" required maxLength={500} value={evidence} onChange={(_, data) => setEvidence(data.value)} />
    {error && <p role="alert" className="error">{error}</p>}
    <div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary">Tinjau penerimaan retur</Button></div>
  </form>
    {operation && <WarehouseCommandDialog title="Konfirmasi penerimaan retur" confirmLabel="Catat retur" command={operation} onDone={onSaved} onReload={onReload} onClose={() => setOperation(null)}
      summary={<><p>{source.code} · {returnItemLabel(source.item)}</p><p><WarehouseQuantity value={source.quantityBase} unit={source.baseUnit} /> → {destination && locationLabel(destination)}</p>
        <p>Bukti: {evidence}</p><p>{source.origin === 'MATERIAL_RESIDUAL' ? 'Buka inspeksi atas sisa yang sudah diterima; stok fisik tidak ditambahkan lagi.' : 'Terima perangkat ke karantina dengan identitas dan kepemilikan asal.'}</p></>} />}
  </>
}
