import { useId, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { saveSku } from '@/api/warehouse/masters'
import type { WarehouseSku } from '@/api/warehouse/models'
import { displayUnit, formatBaseQuantity, quantityFromInput } from '@/api/warehouse/quantity'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, SelectField, TextField } from '@/components/atoms'
import { Modal } from '@/components/molecules/Modal'
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
    <Modal title={readOnly ? 'Detail barang' : row ? 'Ubah barang' : 'Tambah barang'} onClose={onClose} wide footer={<>
      <Button onClick={onClose}>{readOnly ? 'Tutup' : 'Batal'}</Button>{!readOnly && <Button variant="primary" type="submit" form={formId}>Tinjau perubahan</Button>}
    </>}>
      <form id={formId} className="stack" onSubmit={prepare}>
        {row && <p className="muted">Tersimpan: {row.name} · Revisi {row.revision}</p>}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(16rem, 100%), 1fr))', gap: '1rem' }}>
          <TextField label="Kode barang" required pattern="[A-Z0-9][A-Z0-9._-]{0,63}" maxLength={64} value={code} disabled={readOnly} onChange={(_, data) => setCode(data.value.toUpperCase())} hint="Huruf besar, angka, titik, garis bawah atau tanda hubung." />
          <TextField label="Nama barang" required maxLength={200} value={name} disabled={readOnly} onChange={(_, data) => setName(data.value)} />
          <SelectField label="Pelacakan" value={tracking} disabled={readOnly} onChange={(_, data) => { const next = data.value as typeof tracking; setTracking(next); if (next === 'SERIAL') { setUnit('EA'); setMinimum('0') } }}>
            {Object.entries(TRACKING_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </SelectField>
          <SelectField label="Satuan" value={unit} disabled={readOnly || tracking === 'SERIAL'} onChange={(_, data) => { setUnit(data.value as typeof unit); setMinimum('0') }}>
            <option value="EA">Unit</option><option value="MM">Metre (ketelitian 0,001 m)</option>
          </SelectField>
          <TextField label="Kategori" maxLength={100} value={category} disabled={readOnly} onChange={(_, data) => setCategory(data.value)} />
          <TextField label="Model" maxLength={200} value={model} disabled={readOnly} onChange={(_, data) => setModel(data.value)} />
          <WarehouseQuantityField label="Stok minimum" value={minimum} unit={unit} allowZero disabled={readOnly} onChange={setMinimum} />
        </div>
        <fieldset disabled={readOnly}><legend>Cara penyerahan yang diizinkan</legend>{(['LOAN', 'SALE'] as const).map(mode => <Checkbox key={mode} label={mode === 'LOAN' ? 'Pinjaman (milik ISP)' : 'Penjualan (milik pelanggan)'} checked={ownership.includes(mode)}
          onChange={(_, data) => setOwnership(current => data.checked === true ? [...current, mode] : current.filter(item => item !== mode))} />)}</fieldset>
        <Checkbox label="Wajib diperiksa sebelum tersedia" disabled={readOnly} checked={inspection} onChange={(_, data) => setInspection(data.checked === true)} />
        <p className="muted">Satuan dan pelacakan barang yang sudah memiliki stok atau riwayat tidak dapat diubah.</p>
        {error && <p role="alert" className="error">{error}</p>}
      </form>
    </Modal>
    {operation && <WarehouseCommandDialog title="Simpan barang" command={operation} confirmLabel="Simpan barang" onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p><strong>{name.trim()}</strong> · {code.trim()}{row && ` · Revisi ${row.revision}`}</p><p>{TRACKING_LABELS[tracking]} · {displayUnit(unit)} · Minimum {minimum} {displayUnit(unit)}</p>
        <p>{inspection ? 'Wajib pemeriksaan' : 'Pemeriksaan sesuai penerimaan'} · {ownership.map(mode => mode === 'LOAN' ? 'Pinjaman' : 'Penjualan').join(', ')}</p></>} />}
  </>
}
