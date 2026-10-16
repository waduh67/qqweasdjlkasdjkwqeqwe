import { useEffect, useMemo, useRef, useState } from 'react'
import { Field } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import { listNas, previewPppSecrets, type NasView, type PppSecretPreview } from '@/api/bng'
import { listPlans, type PlanView, type ServiceType } from '@/api/catalog'
import { migrateCustomer, localToday, type MigrationRequest } from '@/api/customerMigration'
import { CUSTOMER_CSV_COLUMNS, stageCustomerImport, commitCustomerImport, customerImportStatus, importPppoe, downloadCustomerImportReport, type CustomerImportBatchView, type ImportPppoeResult } from '@/api/onboarding'
import { Button, SelectField, TextField } from '@/components/atoms'
import { CreationSummary } from '@/components/organisms/CreationReview'
import { ResourceForm, useResourceReviewLock } from '@/components/organisms/ResourceForm'
import { ResourceStep } from '@/components/organisms/ResourceStep'
import { DataTable, LocationPicker } from '@/components/organisms'
import { CustomerAreaField } from '@/components/organisms/customer/CustomerAreaField'
import { useCan } from '@/auth/useCan'
import { optionalCustomerLocation } from '@/utils/customerLocation'
import { downloadBlob } from '@/utils/download'

import { MigrationOnuPicker } from './MigrationOnuPicker'
import type { DiscoveredOnuView } from '@/api/monitoring'

type Source = 'manual' | 'csv' | 'mikrotik'
const SOURCE_LABEL = { manual: 'Input manual', csv: 'Berkas CSV', mikrotik: 'MikroTik' }
const initial = { code: '', name: '', phone: '', email: '', address: '', latitude: '', longitude: '', planId: '', price: '', status: 'ACTIVE', installationDate: '', username: '', password: '', authType: 'PPPOE' as ServiceType, framedIp: '' }
const resultLabel: Record<string, string> = { CREATED: 'Ditambahkan', UPDATED: 'Diperbarui', SKIPPED: 'Dilewati', FAILED: 'Gagal' }

export function CustomerMigrationPanel({ initialSource = 'manual', batchId, onBatch, onClose, onCreated }: {
  initialSource?: Source; batchId?: string | null; onBatch: (id: string) => void; onClose: () => void; onCreated: (customerId?: string) => void
}) {
  const { can } = useCan()
  const [source, setSource] = useState<Source>(initialSource)
  const [draft, setDraft] = useState(initial)
  const [areaId, setAreaId] = useState<string | null>(null)
  const [nasId, setNasId] = useState('')
  const [onu, setOnu] = useState<DiscoveredOnuView | null>(null)
  const [plans, setPlans] = useState<PlanView[]>([])
  const [routers, setRouters] = useState<NasView[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [billing, setBilling] = useState('later')
  const [csvBilling, setCsvBilling] = useState('csv')
  const [firstInvoice, setFirstInvoice] = useState('')
  const [billingDay, setBillingDay] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [batch, setBatch] = useState<CustomerImportBatchView | null>(null)
  const [resumeId] = useState(batchId)
  const [rows, setRows] = useState<PppSecretPreview[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [mapping, setMapping] = useState<Record<string, string>>({})
  const [result, setResult] = useState<ImportPppoeResult | null>(null)
  const [reviewing, setReviewing] = useState(!!batchId)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const command = useRef<MigrationRequest | null>(null)
  const operation = useRef(crypto.randomUUID())
  const profiles = useMemo(() => [...new Set(rows.filter(row => selected.has(row.name)).map(row => row.profile || ''))], [rows, selected])
  const canMikrotik = can('bng.nas.manage') && can('bng.nas.view')
  const canMigrate = can('customer.customer.create') && can('customer.subscription.update')
  const set = (patch: Partial<typeof initial>) => setDraft(previous => ({ ...previous, ...patch }))
  const useBillingDefaults = source === 'csv' ? csvBilling === 'defaults' : billing === 'start'
  const defaults = { firstInvoiceOn: useBillingDefaults ? firstInvoice : null, billingDay: useBillingDefaults && billingDay ? Number(billingDay) : null, areaId }
  const location = optionalCustomerLocation(draft.longitude, draft.latitude)
  useEffect(() => {
    let active = true
    setLoading(true); setLoadError('')
    void Promise.all([listPlans(), can('bng.nas.view') ? listNas() : Promise.resolve([])])
      .then(([catalog, nas]) => { if (active) { setPlans(catalog.filter(plan => plan.active)); setRouters(nas) } })
      .catch(() => { if (active) setLoadError('Data layanan belum berhasil dimuat.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [can, loadAttempt])
  useEffect(() => {
    if (!resumeId) return
    let active = true
    void customerImportStatus(resumeId).then(value => { if (active) { setBatch(value); setSource('csv'); setReviewing(true) } }).catch(() => { if (active) setError('Status impor belum berhasil dimuat.') })
    return () => { active = false }
  }, [resumeId])
  useEffect(() => {
    if (!batch || !['PROCESSING', 'RETRYABLE_FAILED'].includes(batch.state)) return
    let active = true
    const timer = window.setTimeout(() => {
      void customerImportStatus(batch.id).then(value => { if (active) { setBatch(value); setError('') } }).catch(() => { if (active) setError('Status impor belum berhasil dimuat. Tutup lalu buka kembali untuk memeriksa.') })
    }, 2000)
    return () => { active = false; clearTimeout(timer) }
  }, [batch])
  async function readRouter() {
    setBusy(true); setError('')
    try { const accounts = await previewPppSecrets(nasId); setRows(accounts); setSelected(new Set(accounts.map(row => row.name))) }
    catch (e) { setError(e instanceof ApiError ? e.message : 'Akun MikroTik belum berhasil dimuat.') }
    finally { setBusy(false) }
  }
  async function review() {
    setError('')
    if (!canMigrate) return
    if (source === 'csv') {
      if (!file) { setError('Pilih berkas CSV.'); return }
      setBusy(true)
      try { const staged = await stageCustomerImport(file, crypto.randomUUID(), 'ALREADY_INSTALLED', defaults); setBatch(staged); onBatch(staged.id); setReviewing(true) }
      catch (e) { setError(e instanceof ApiError ? e.message : 'Berkas belum berhasil diunggah.') }
      finally { setBusy(false) }
    } else setReviewing(true)
  }
  async function submit() {
    if (busy) return
    setBusy(true); setError('')
    try {
      if (source === 'manual') {
        command.current ??= {
          operationKey: operation.current, onuSerial: onu?.serialNumber ?? null,
          customer: { code: draft.code || null, name: draft.name.trim(), phone: draft.phone || null, email: draft.email || null, address: draft.address.trim(), areaId, location: location.location, planId: draft.planId, monthlyFeeOverride: draft.price ? Number(draft.price) : null },
          status: draft.status === 'SUSPENDED' ? 'SUSPENDED' : 'ACTIVE', installationDate: draft.installationDate || null,
          firstInvoiceOn: defaults.firstInvoiceOn, billingDay: defaults.billingDay,
          username: draft.username || null, password: draft.password || null, authType: draft.authType, nasId: nasId || null, framedIp: draft.framedIp || null,
        }
        const created = await migrateCustomer(command.current)
        onCreated(created.customerId)
      } else if (source === 'csv' && batch) {
        setBatch(await commitCustomerImport(batch.id, { commitOperationKey: `migration:${batch.id}`, commitHash: batch.sha256 }))
      } else if (source === 'mikrotik') {
        setResult(await importPppoe({ operationKey: operation.current, nasId, source: 'NAS', onlyNames: [...selected], skipDisabled: false, profilePlanId: mapping, defaultPlanId: draft.planId || null, defaultAddress: draft.address || null, defaultLocation: location.location, areaId, firstInvoiceOn: defaults.firstInvoiceOn, billingDay: defaults.billingDay, installationDate: draft.installationDate || null }))
      }
      setUncertain(false)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Hasil permintaan belum diketahui. Coba lagi untuk memeriksa hasilnya.')
      setUncertain(!(e instanceof ApiError))
      if (e instanceof ApiError) command.current = null
    } finally { setBusy(false) }
  }
  const finished = result ?? batch?.result
  const summary = <div className="stack">
    <ReviewLock locked={uncertain} />
    {error && <p role="alert" className="error">{error}</p>}
    <CreationSummary rows={source === 'manual' ? [
      ['Pelanggan', draft.name], ['Status layanan', draft.status === 'ACTIVE' ? 'Aktif' : 'Ditangguhkan'], ['Paket', plans.find(plan => plan.id === draft.planId)?.name],
      ['Harga bulanan', `Rp ${Number(draft.price || plans.find(plan => plan.id === draft.planId)?.price || 0).toLocaleString('id-ID')}`],
      ['Mulai layanan', draft.installationDate || 'Belum diketahui'], ['Mulai penagihan', defaults.firstInvoiceOn || 'Belum dikelola'],
      ['Tanggal tagih setiap bulan', defaults.firstInvoiceOn ? defaults.billingDay || Number(defaults.firstInvoiceOn.slice(-2)) : 'Belum dikelola'],
      ['ONU', onu?.serialNumber || 'Belum dihubungkan'], ['Koordinat', draft.latitude && draft.longitude ? `${draft.latitude}, ${draft.longitude}` : 'Belum ditentukan'], ['Username', draft.username || 'Belum diisi'],
    ] : [['Sumber', SOURCE_LABEL[source]], [source === 'csv' ? 'Berkas' : 'BRAS', source === 'csv' ? file?.name || 'Impor tersimpan' : routers.find(router => router.id === nasId)?.name], ['Pelanggan', source === 'csv' ? batch?.rowCount : selected.size], ...(source === 'csv' ? [] : [['Mulai penagihan', defaults.firstInvoiceOn || 'Belum dikelola'] as [string, string]])]} />
    {!finished && <p className="muted">Akun jaringan lama tetap digunakan. RADIUS dapat disiapkan dari detail pelanggan.</p>}
    {batch && !finished && (batch.preview?.length ?? 0) > 0 && <div className="stack"><p>{batch.preview!.length} dari {batch.rowCount} pelanggan</p><DataTable rows={batch.preview!} rowKey={row => String(row.row)} columns={[
      { key: 'identity', header: 'Kode / username', minWidth: 160, cell: row => row.identity },
      { key: 'onu', header: 'ONU', minWidth: 160, cell: row => row.onuSerial || '—' },
      { key: 'name', header: 'Nama', minWidth: 170, cell: row => row.name || '—' },
      { key: 'packageName', header: 'Paket', minWidth: 150, cell: row => row.packageName || '—' },
      { key: 'status', header: 'Status', minWidth: 130, cell: row => ['SUSPENDED', 'ISOLATED', 'TERISOLIR', 'DITANGGUHKAN'].includes(row.status.toUpperCase()) ? 'Ditangguhkan' : row.status.toUpperCase() === 'ACTIVE' || row.status.toUpperCase() === 'AKTIF' ? 'Aktif' : row.status },
      { key: 'firstInvoiceOn', header: 'Mulai penagihan', minWidth: 160, cell: row => row.firstInvoiceOn || 'Belum dikelola' },
      { key: 'billingDay', header: 'Tanggal tiap bulan', minWidth: 160, cell: row => row.firstInvoiceOn ? row.billingDay ?? Number(row.firstInvoiceOn.slice(-2)) : '—' },
      { key: 'monthlyFee', header: 'Harga bulanan', minWidth: 140, cell: row => row.monthlyFee == null ? 'Sesuai paket' : `Rp ${Number(row.monthlyFee).toLocaleString('id-ID')}` },
    ]} /></div>}
    {batch && batch.errors.length > 0 && <><p role="alert">Perbaiki data berikut, lalu unggah kembali.</p><DataTable rows={batch.errors} rowKey={row => `${row.row}:${row.column}:${row.code}`} columns={[{ key: 'row', header: 'Baris', cell: row => row.row }, { key: 'column', header: 'Kolom', cell: row => row.column || '—' }, { key: 'message', header: 'Keterangan', cell: row => row.message }]} /></>}
    {batch && ['PROCESSING', 'RETRYABLE_FAILED'].includes(batch.state) && <p role="status">Migrasi sedang diproses. Anda dapat menutup panel ini dan memeriksa hasilnya kembali.</p>}
    {batch?.state === 'PERMANENT_FAILED' && <p role="alert">Migrasi belum selesai. Unduh laporan untuk memeriksa hasilnya.</p>}
    {finished && <><p role="status">{finished.created} ditambahkan · {finished.skipped} dilewati · {finished.failed} gagal</p><DataTable rows={finished.rows} rowKey={row => row.username} columns={[{ key: 'username', header: 'Pelanggan', minWidth: 180, cell: row => row.username }, { key: 'status', header: 'Hasil', cell: row => resultLabel[row.status] || row.status }, { key: 'message', header: 'Keterangan', minWidth: 240, cell: row => row.message || '—' }]} /></>}
    {batch && <div><Button onClick={() => void downloadCustomerImportReport(batch.id).then(blob => downloadBlob(blob, 'hasil-migrasi.csv')).catch(() => setError('Laporan belum berhasil diunduh.'))}>Unduh laporan</Button></div>}
  </div>
  const steps = [{ key: 'source', label: 'Sumber' }, ...(source === 'manual' ? [{ key: 'customer', label: 'Pelanggan' }, { key: 'service', label: 'Layanan' }] : source === 'mikrotik' ? [
    { key: 'accounts', label: 'Akun', validate: () => selected.size ? null : 'Pilih akun yang akan dimigrasikan.' },
    { key: 'service', label: 'Paket', validate: () => profiles.every(profile => mapping[profile] || draft.planId) ? null : 'Pilih paket untuk setiap profil.' },
  ] : []), { key: 'billing', label: 'Penagihan' }, ...(source !== 'csv' ? [{ key: 'location', label: 'Lokasi', validate: () => location.error }] : []), ...(source === 'manual' ? [{ key: 'network', label: 'Akun lama' }, ...(can('customer.onu.assign') && can('monitoring.provisioning.view') ? [{ key: 'device', label: 'Perangkat' }] : [])] : [])]
  return <ResourceForm title="Migrasi pelanggan" onClose={onClose} onBack={() => { if (!uncertain) { setReviewing(false); command.current = null } }} loading={loading} busy={busy} readOnly={!canMigrate || !!loadError}
    steps={steps} onReview={() => void review()} review={reviewing ? summary : undefined}
    reviewFooter={<>{finished ? <Button variant="primary" onClick={() => onCreated()}>Selesai</Button> : <>
      <Button disabled={busy || uncertain || !!(batch && batch.state !== 'STAGED')} onClick={() => { setReviewing(false); command.current = null }}>Sebelumnya</Button>
      <Button disabled={busy || uncertain} onClick={onClose}>Tutup</Button>
      <Button variant="primary" disabled={busy || !canMigrate || (source === 'csv' && (!batch || batch.errors.length > 0 || batch.state !== 'STAGED'))} onClick={() => void submit()}>{busy ? 'Memproses…' : uncertain ? 'Coba lagi' : 'Migrasikan'}</Button>
    </>}</> }>
    <div className="stack">
      {error && <p role="alert" className="error">{error}</p>}
      {!canMigrate && <p role="alert">Akses migrasi pelanggan dibatasi. Hubungi administrator.</p>}
      {loadError && <div role="alert"><p>{loadError}</p><Button onClick={() => setLoadAttempt(value => value + 1)}>Coba lagi</Button></div>}
      <ResourceStep step="source">
        <p>Pindahkan pelanggan yang sudah berlangganan.</p>
        <SelectField label="Sumber data" value={source} onChange={(_, data) => { setSource(data.value as Source); setBatch(null); setError('') }}>
          <option value="manual">Input manual</option>{can('customer.customer.update') && <option value="csv">Berkas CSV</option>}{canMikrotik && <option value="mikrotik">MikroTik</option>}
        </SelectField>
        {source === 'csv' && <><Field label="Berkas CSV" required><input type="file" accept=".csv,text/csv" required onChange={event => setFile(event.target.files?.[0] || null)} /></Field><div><Button onClick={() => downloadBlob(new Blob([`\uFEFF${CUSTOMER_CSV_COLUMNS.join(',')}\r\n`], { type: 'text/csv;charset=utf-8' }), 'template-migrasi.csv')}>Unduh template</Button></div><p className="muted">Isi nama, alamat, paket, serta kode pelanggan atau username. Gunakan ACTIVE atau SUSPENDED pada kolom service_status. Kolom onu_serial dapat diisi dengan nomor seri dari hasil deteksi ONU.</p><CustomerAreaField value={areaId} onChange={setAreaId} /></>}
        {source === 'mikrotik' && <><SelectField label="BRAS MikroTik" required value={nasId} onChange={(_, data) => { setNasId(data.value); setRows([]); setSelected(new Set()) }}><option value="">Pilih BRAS</option>{routers.filter(router => router.vendor === 'MIKROTIK').map(router => <option key={router.id} value={router.id}>{router.name}</option>)}</SelectField><div><Button disabled={!nasId || busy || !canMikrotik} onClick={() => void readRouter()}>Muat akun</Button></div></>}
      </ResourceStep>
      {source === 'manual' && <ResourceStep step="customer"><TextField label="Kode pelanggan" value={draft.code} maxLength={40} placeholder="Otomatis" onChange={(_, data) => set({ code: data.value })} /><TextField label="Nama" required maxLength={150} value={draft.name} onChange={(_, data) => set({ name: data.value })} /><TextField label="Telepon" value={draft.phone} maxLength={30} onChange={(_, data) => set({ phone: data.value })} /><TextField label="Email" type="email" value={draft.email} onChange={(_, data) => set({ email: data.value })} /></ResourceStep>}
      {source === 'mikrotik' && <ResourceStep step="accounts"><p>Akun nonaktif akan dicatat sebagai layanan ditangguhkan.</p><DataTable rows={rows} rowKey={row => row.name} selection={{ selected, onChange: setSelected }} columns={[{ key: 'name', header: 'Username', cell: row => row.name }, { key: 'profile', header: 'Profil', cell: row => row.profile || '—' }, { key: 'status', header: 'Status', cell: row => row.disabled ? 'Ditangguhkan' : 'Aktif' }]} /></ResourceStep>}
      {source !== 'csv' && <ResourceStep step="service">
        {source === 'manual' && <SelectField label="Status layanan" value={draft.status} onChange={(_, data) => set({ status: data.value })}><option value="ACTIVE">Aktif</option><option value="SUSPENDED">Ditangguhkan</option></SelectField>}
        <SelectField label={source === 'manual' ? 'Paket internet' : 'Paket bawaan'} required={source === 'manual'} value={draft.planId} onChange={(_, data) => set({ planId: data.value })}><option value="">Pilih paket</option>{plans.map(plan => <option key={plan.id} value={plan.id}>{plan.name}</option>)}</SelectField>
        {source === 'mikrotik' && profiles.filter(Boolean).map(profile => <SelectField key={profile} label={`Profil ${profile}`} value={mapping[profile] || ''} onChange={(_, data) => setMapping(previous => ({ ...previous, [profile]: data.value }))}><option value="">Paket bawaan</option>{plans.map(plan => <option key={plan.id} value={plan.id}>{plan.name}</option>)}</SelectField>)}
        {source === 'manual' && <TextField label="Harga bulanan (Rp)" type="number" min={0} step="0.01" value={draft.price} placeholder={String(plans.find(plan => plan.id === draft.planId)?.price || '')} onChange={(_, data) => set({ price: data.value })} />}
        <TextField label="Mulai berlangganan (opsional)" type="date" max={localToday()} value={draft.installationDate} onChange={(_, data) => set({ installationDate: data.value })} />
      </ResourceStep>}
      {source === 'manual' && can('customer.onu.assign') && can('monitoring.provisioning.view') && <ResourceStep step="device"><MigrationOnuPicker value={onu} onChange={setOnu} /><p className="muted">Pilih ONU yang sudah digunakan pelanggan. Dapat dihubungkan nanti dari detail pelanggan.</p></ResourceStep>}
      <ResourceStep step="billing">
        {source === 'csv' ? <>
          <SelectField label="Jadwal penagihan" value={csvBilling} onChange={(_, data) => setCsvBilling(data.value)}>
            <option value="csv">Sesuai CSV</option><option value="defaults">Sesuai CSV, dengan tanggal bawaan</option>
          </SelectField>
          <CreationSummary rows={[
            ['first_invoice_on', 'Mulai penagihan (YYYY-MM-DD)'],
            ['next_billing', 'Tanggal setiap bulan (1–31)'],
          ]} />
          <p className="muted">{useBillingDefaults ? 'Tanggal bawaan hanya digunakan untuk kolom CSV yang kosong.' : 'Pelanggan tanpa tanggal mulai belum ditagih.'}</p>
        </> : <SelectField label="Penagihan" value={billing} onChange={(_, data) => setBilling(data.value)}><option value="later">Belum kelola tagihan</option><option value="start">Mulai kelola tagihan</option></SelectField>}
        {useBillingDefaults && <><TextField label="Mulai penagihan di aplikasi ini" type="date" required min={localToday()} value={firstInvoice} onChange={(_, data) => setFirstInvoice(data.value)} /><TextField label="Tanggal tagih setiap bulan" type="number" min={1} max={31} value={billingDay} placeholder={firstInvoice ? String(Number(firstInvoice.slice(-2))) : 'Mengikuti tanggal mulai penagihan'} onChange={(_, data) => setBillingDay(data.value)} /><p className="muted">Pilih jadwal berikutnya yang belum ditagih di sistem lama.</p></>}
        {source !== 'csv' && !useBillingDefaults && <p className="muted">Penagihan dapat dimulai nanti dari detail pelanggan.</p>}
      </ResourceStep>
      {source !== 'csv' && <ResourceStep step="location"><TextField label="Alamat" required={source === 'manual'} value={draft.address} onChange={(_, data) => set({ address: data.value })} /><CustomerAreaField value={areaId} onChange={setAreaId} /><Field label="Koordinat (opsional)"><LocationPicker longitude={draft.longitude} latitude={draft.latitude} onChange={(longitude, latitude) => set({ longitude, latitude })} /></Field></ResourceStep>}
      {source === 'manual' && <ResourceStep step="network"><p className="muted">Simpan akun yang digunakan pelanggan saat ini.</p><TextField label="Username" value={draft.username} onChange={(_, data) => set({ username: data.value })} /><TextField label="Password" type="password" autoComplete="new-password" value={draft.password} onChange={(_, data) => set({ password: data.value })} /><SelectField label="Jenis koneksi" value={draft.authType} onChange={(_, data) => set({ authType: data.value as ServiceType })}><option value="PPPOE">PPPoE</option><option value="HOTSPOT">Hotspot</option><option value="DHCP">DHCP</option><option value="STATIC">IP statis</option></SelectField><SelectField label="BRAS" value={nasId} onChange={(_, data) => setNasId(data.value)}><option value="">Belum ditentukan</option>{routers.map(router => <option key={router.id} value={router.id}>{router.name}</option>)}</SelectField>{['STATIC', 'DHCP'].includes(draft.authType) && <TextField label="Alamat IP" value={draft.framedIp} onChange={(_, data) => set({ framedIp: data.value })} />}</ResourceStep>}
    </div>
  </ResourceForm>
}
function ReviewLock({ locked }: { locked: boolean }) { useResourceReviewLock(locked); return null }
