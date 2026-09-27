import { ResourceForm } from '@/components/organisms/ResourceForm'
import { useId, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { saveSku } from '@/api/warehouse/masters'
import type { WarehouseSku } from '@/api/warehouse/models'
import { displayUnit, formatBaseQuantity, quantityFromInput } from '@/api/warehouse/quantity'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'

const TRACKING_LABELS = { SERIAL: 'Per perangkat (serial)', LOT: 'Per lot / gulungan', BULK: 'Curah / jumlah' }
export function WarehouseSkuEditor({ row, readOnly, onClose, onSaved, onReload }: { row: WarehouseSku | null; readOnly: boolean; onClose: () => void; onSaved: () => void; onReload: () => void }) {
  const formId = useId()
  const [code, setCode] = useState(row?.code ?? '')
  const [name, setName] = useState(row?.name ?? '')
  const [tracking, setTracking] = useState<WarehouseSku['tracking']>(row?.tracking ?? 'BULK')
  const [unit, setUnit] = useState<WarehouseSku['baseUnit']>(row?.baseUnit ?? 'EA')
  const [category, setCategory] = useState(row?.category ?? '')
  const [model, setModel] = useState(row?.model ?? '')
  const [ownership, setOwnership] = useState<WarehouseSku['allowedOwnershipModes']>(row?.allowedOwnershipModes ?? ['LOAN', 'SALE'])
  const [inspection, setInspection] = useState(row?.inspectionRequired ?? true)
  const [minimum, setMinimum] = useState(row ? formatBaseQuantity(row.minimumQuantityBase, row.baseUnit) : '0')
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseSku> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault(); if (readOnly) return
    try {
      if (ownership.length === 0) throw new Error('Pilih sedikitnya satu cara penyerahan.')
      const quantity = quantityFromInput(minimum, unit, true)
      setOperation(saveSku({ code: code.trim(), name: name.trim(), tracking, baseUnit: unit, category: category.trim() || null,
        model: model.trim() || null, allowedOwnershipModes: ownership, inspectionRequired: inspection, minimumQuantityBase: quantity,
        ...(row ? { expectedRevision: row.revision } : {}) }, row?.id))
      setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa isian barang.') }
  }
  return <>
    <ResourceForm readOnly={readOnly} editing={!!row} onBack={() => setOperation(null)} review={operation && (<WarehouseCommandDialog embedded title="Simpan barang" command={operation} confirmLabel="Simpan barang" onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p><strong>{name.trim()}</strong> · {code.trim()}{row && ` · Revisi ${row.revision}`}</p><p>{TRACKING_LABELS[tracking]} · {displayUnit(unit)} · Minimum {minimum} {displayUnit(unit)}</p>
        <p>{inspection ? 'Wajib pemeriksaan' : 'Pemeriksaan sesuai penerimaan'} · {ownership.map(mode => mode === 'LOAN' ? 'Pinjaman' : 'Penjualan').join(', ')}</p></>} />)} title={readOnly ? 'Detail barang' : row ? 'Ubah barang' : 'Tambah barang'} onClose={onClose} footer={<>
      <Button onClick={onClose}>{readOnly ? 'Tutup' : 'Batal'}</Button>{!readOnly && <Button variant="primary" type="submit" form={formId}>{row ? 'Tinjau + simpan' : 'Tinjau + buat'}</Button>}
    </>}>
      <form id={formId} className="stack" onSubmit={prepare}>
        {row && <p className="muted">Tersimpan: {row.name} · Revisi {row.revision}</p>}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(16rem, 100%), 1fr))', gap: '1rem' }}>
          <TextField label="Kode barang" required pattern="[A-Z0-9][A-Z0-9._-]{0,63}" maxLength={64} value={code} disabled={readOnly} onChange={(_, data) => setCode(data.value.toUpperCase())} hint="Contoh: ONU-001." />
          <TextField label="Nama barang" required maxLength={200} value={name} disabled={readOnly} onChange={(_, data) => setName(data.value)} />
          <SelectField label="Pelacakan" value={tracking} disabled={readOnly} onChange={(_, data) => { const next = data.value as typeof tracking; setTracking(next); if (next === 'SERIAL') { setUnit('EA'); setMinimum('0') } }}>
            {Object.entries(TRACKING_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </SelectField>
          <SelectField label="Satuan" value={unit} disabled={readOnly || tracking === 'SERIAL'} onChange={(_, data) => { setUnit(data.value as typeof unit); setMinimum('0') }}>
            <option value="EA">Unit</option><option value="MM">Meter (0,001 m)</option>
          </SelectField>
          <TextField label="Kategori" maxLength={100} value={category} disabled={readOnly} onChange={(_, data) => setCategory(data.value)} />
          <TextField label="Model" maxLength={200} value={model} disabled={readOnly} onChange={(_, data) => setModel(data.value)} />
          <WarehouseQuantityField label="Stok minimum" value={minimum} unit={unit} allowZero disabled={readOnly} onChange={setMinimum} />
        </div>
        <fieldset disabled={readOnly}><legend>Kepemilikan</legend>{(['LOAN', 'SALE'] as const).map(mode => <Checkbox key={mode} label={mode === 'LOAN' ? 'Pinjaman (milik ISP)' : 'Penjualan (milik pelanggan)'} checked={ownership.includes(mode)}
          onChange={(_, data) => setOwnership(current => data.checked === true ? [...current, mode] : current.filter(item => item !== mode))} />)}</fieldset>
        <Checkbox label="Wajib diperiksa sebelum tersedia" disabled={readOnly} checked={inspection} onChange={(_, data) => setInspection(data.checked === true)} />
        {row && <p className="muted">Satuan dan pelacakan tidak dapat diubah setelah barang memiliki riwayat.</p>}
        {error && <p role="alert" className="error">{error}</p>}
      </form>
    </ResourceForm>

  </>
}
