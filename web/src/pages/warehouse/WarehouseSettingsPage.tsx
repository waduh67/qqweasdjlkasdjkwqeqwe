import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { getPolicyDetails, savePolicy, type PolicyDetails, type PolicyInput, type PolicyVersion } from '@/api/warehouse/policy'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { buildPolicy, policyFingerprint, policyRulesDraft, type PolicyLocation } from './policyDraft'
import { locationLabel, receiptLocations } from './receiptChoices'
import { WarehousePolicyRules } from './WarehousePolicyRules'
import { WarehousePolicyPreview } from './WarehousePolicyPreview'
import { WarehousePolicyHistory } from './WarehousePolicyHistory'
import { WarehouseDelegations } from './WarehouseDelegations'

export function WarehouseSettingsPage() {
  const { can } = useCan()
  return <div className="stack"><PageHeader title="Setelan Gudang" subtitle="Atur pemeriksa independen, batas persetujuan, dan akses lokasi." />
    {can('inventory.location.view') && <Link to="/warehouse/catalog?tab=access">Kelola akses pengguna ke gudang</Link>}
    {can('inventory.approval.view') ? <><PolicyPanel /><WarehousePolicyHistory /><WarehouseDelegations /></> : <p role="status">Izin lihat persetujuan diperlukan untuk membaca kebijakan gudang.</p>}
  </div>
}
function PolicyPanel() {
  const result = useWarehouseQuery(getPolicyDetails), [editing, setEditing] = useState(false), { can } = useCan()
  const refresh = () => { setEditing(false); result.reload() }
  return <section className="stack" aria-label="Kebijakan persetujuan gudang"><Button onClick={refresh}>Muat ulang kebijakan</Button>
    <WarehouseState {...result}>{settings => <><section className="card stack" aria-label="Kebijakan tersimpan"><h2>Kebijakan tersimpan</h2>
      {settings.current ? <><p>Versi {settings.current.revision} · <WarehouseTime value={settings.current.createdAt} /></p><WarehousePolicyPreview locations={settings.references.locations} rules={policyRulesDraft(settings)} currency={settings.current.currency} expiry={String(settings.current.expiryHours)} /></>
        : <p>Belum ada kebijakan persetujuan. Siapkan pengguna pemeriksa dengan izin putuskan persetujuan dan cakupan semua lokasi kebijakan.</p>}
      {!editing && can('inventory.approval.manage') && <Button variant="primary" disabled={!settings.current && !can('inventory.location.view')} onClick={() => setEditing(true)}>{settings.current ? 'Ubah kebijakan persetujuan' : 'Buat kebijakan persetujuan'}</Button>}
      {!can('inventory.approval.manage') && <p className="muted">Akses baca saja. Perubahan memerlukan izin kelola persetujuan.</p>}
    </section>{editing && <PolicyEditor settings={settings} onClose={() => setEditing(false)} onDone={refresh} />}</>}</WarehouseState>
  </section>
}
function PolicyEditor({ settings, onClose, onDone }: { settings: PolicyDetails; onClose: () => void; onDone: () => void }) {
  const { can } = useCan(), [currency, setCurrency] = useState(settings.current?.currency ?? 'IDR'), [expiry, setExpiry] = useState(String(settings.current?.expiryHours ?? 24))
  const [locations, setLocations] = useState<PolicyLocation[]>(settings.references.locations), [location, setLocation] = useState<PolicyLocation | null>(null)
  const [rules, setRules] = useState(() => policyRulesDraft(settings)), [error, setError] = useState('')
  const [review, setReview] = useState<{ input: PolicyInput; command: WarehouseCommand<PolicyVersion> } | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      const input = buildPolicy(settings, currency, expiry, locations, rules)
      if (settings.current && policyFingerprint(input) === policyFingerprint(settings.current)) throw new Error('Belum ada perubahan pada kebijakan tersimpan.')
      setReview({ input, command: savePolicy(input) }); setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa aturan persetujuan.') }
  }
  return <><form className="card stack" aria-label="Draft kebijakan persetujuan" onSubmit={prepare}><h2>Draft perubahan kebijakan</h2>
    <p>Berdasarkan versi tersimpan {settings.current?.revision ?? 0}. Perubahan mulai berlaku setelah disimpan. Pengajuan yang sudah ada memakai versi yang dicatat saat diajukan.</p>
    <TextField label="Mata uang kebijakan" required maxLength={3} value={currency} onChange={(_, data) => setCurrency(data.value.toUpperCase())} />
    <TextField label="Masa berlaku persetujuan (jam)" inputMode="numeric" required maxLength={3} value={expiry} onChange={(_, data) => setExpiry(data.value)} />
    <h3>Lokasi kebijakan</h3><ul>{locations.map(row => <li key={row.id} className="row wrap"><span>{locationLabel(row)}</span><Button type="button" aria-label={`Hapus lokasi ${locationLabel(row)}`} onClick={() => setLocations(locations.filter(item => item.id !== row.id))}>Hapus lokasi</Button></li>)}</ul>
    {can('inventory.location.view') && <><WarehousePicker label="Lokasi kebijakan" load={receiptLocations} value={location} name={locationLabel} optional eligible={row => !locations.some(selected => selected.id === row.id)} onChange={setLocation} />
      <Button type="button" disabled={!location || locations.length >= 100 || locations.some(row => row.id === location.id)} onClick={() => { if (location) { setLocations([...locations, location]); setLocation(null) } }}>Tambahkan lokasi kebijakan</Button></>}
    <p>Setiap tahap memerlukan pemeriksa independen. Pilihan pengguna dan role dibatasi izin serta cakupan seluruh lokasi di atas. Satu orang tidak dapat menyelesaikan dua tahap pada pengajuan yang sama.</p>
    <WarehousePolicyRules rules={rules} locations={locations} onChange={setRules} />
    {error && <p role="alert" className="error">{error}</p>}<div className="row wrap"><Button type="button" onClick={onClose}>Batalkan perubahan kebijakan</Button><Button type="submit" variant="primary">Tinjau kebijakan</Button></div>
  </form>{review && <WarehouseCommandDialog title="Konfirmasi perubahan kebijakan" confirmLabel="Simpan kebijakan" command={review.command} onDone={onDone} onReload={onDone} onClose={() => setReview(null)}
    summary={<><h3>Tersimpan · Versi {review.input.expectedRevision}</h3>{settings.current ? <WarehousePolicyPreview locations={settings.references.locations} rules={policyRulesDraft(settings)} currency={settings.current.currency} expiry={String(settings.current.expiryHours)} /> : <p>Belum ada kebijakan.</p>}
      <h3>Rencana perubahan</h3><WarehousePolicyPreview locations={locations} rules={rules} currency={currency} expiry={expiry} /><p>Simpan hanya setelah cakupan lokasi, pemeriksa, dan batas setiap tahap sudah sesuai.</p></>} />}</>
}
