import { useEffect, useState, type ReactNode } from 'react'
import { ApiError } from '@/api/client'
import { getCustomerMigration, prepareMigrationRadius, startMigrationBilling, localToday, type MigrationView } from '@/api/customerMigration'
import { listNas, type NasView } from '@/api/bng'
import type { ServiceType } from '@/api/catalog'
import { Button, SelectField, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { ResourceStep } from '@/components/organisms/ResourceStep'
import { CreationSummary } from '@/components/organisms/CreationReview'
import { useCan } from '@/auth/useCan'

export function CustomerMigrationManagement({ customerId, mode, fallback, onUpdated }: { customerId: string; mode: 'billing' | 'network'; fallback?: ReactNode; onUpdated: () => void }) {
  const { can } = useCan()
  const [migration, setMigration] = useState<MigrationView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [open, setOpen] = useState(false)
  useEffect(() => {
    let active = true
    setLoading(true)
    void getCustomerMigration(customerId).then(value => { if (active) { setMigration(value); setError('') } }).catch(() => { if (active) setError('Data migrasi belum berhasil dimuat.') }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [customerId, attempt])
  if (loading) return <p role="status">Memuat…</p>
  if (error) return <div role="alert"><p>{error}</p><Button onClick={() => setAttempt(value => value + 1)}>Coba lagi</Button></div>
  if (!migration || (mode === 'network' && migration.accessId)) return fallback
  return <div className="stack">
    <CreationSummary rows={mode === 'billing' ? [['Penagihan', migration.billingManaged ? 'Dikelola' : 'Belum dikelola'], ['Tagihan pertama', migration.firstInvoiceOn], ['Tanggal tagih', migration.billingDay]] : [['Akun RADIUS', 'Belum disiapkan'], ['Username lama', migration.username], ['Jenis koneksi', migration.authType]]} />
    {mode === 'network' && <p className="muted">Pelanggan masih menggunakan akun jaringan sebelumnya.</p>}
    {((mode === 'billing' && !migration.billingManaged && can('customer.subscription.update')) || (mode === 'network' && can('bng.access.manage'))) && <div><Button onClick={() => setOpen(true)}>{mode === 'billing' ? 'Mulai penagihan' : 'Siapkan RADIUS'}</Button></div>}
    {open && <MigrationAction migration={migration} mode={mode} onClose={() => setOpen(false)} onSaved={value => { setMigration(value); setOpen(false); onUpdated() }} />}
  </div>
}
function MigrationAction({ migration, mode, onClose, onSaved }: { migration: MigrationView; mode: 'billing' | 'network'; onClose: () => void; onSaved: (value: MigrationView) => void }) {
  const [date, setDate] = useState('')
  const [day, setDay] = useState(migration.billingDay ? String(migration.billingDay) : '')
  const [routers, setRouters] = useState<NasView[]>([])
  const [nasId, setNasId] = useState(migration.nasId || '')
  const [username, setUsername] = useState(migration.username || '')
  const [password, setPassword] = useState('')
  const [authType, setAuthType] = useState<ServiceType>(migration.authType)
  const [ip, setIp] = useState(migration.framedIp || '')
  const [review, setReview] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [loading, setLoading] = useState(mode === 'network')
  useEffect(() => {
    if (mode !== 'network') return
    let active = true
    setLoading(true)
    void listNas().then(value => { if (active) { setRouters(value); setError('') } }).catch(() => { if (active) setError('Daftar BRAS belum berhasil dimuat.') }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [mode, loadAttempt])
  const title = mode === 'billing' ? 'Mulai penagihan' : 'Siapkan RADIUS'
  async function save() {
    setBusy(true); setError('')
    try { onSaved(mode === 'billing' ? await startMigrationBilling(migration.customerId, date, day ? Number(day) : null) : await prepareMigrationRadius(migration.customerId, { nasId, username, password: password || undefined, authType, framedIp: ip || null })) }
    catch (e) { setError(e instanceof ApiError ? e.message : 'Hasil permintaan belum diketahui. Coba lagi untuk memeriksa hasilnya.') }
    finally { setBusy(false) }
  }
  return <ResourceForm title={title} busy={busy} loading={loading} onClose={onClose} onBack={() => setReview(false)} onReview={() => setReview(true)} steps={[{ key: 'settings', label: mode === 'billing' ? 'Penagihan' : 'Akun jaringan' }]}
    review={review ? <div className="stack">{error && <p role="alert">{error}</p>}<CreationSummary rows={mode === 'billing' ? [['Tagihan pertama', date], ['Tanggal tagih', day || Number(date.slice(-2))]] : [['Username', username], ['BRAS', routers.find(router => router.id === nasId)?.name], ['Jenis koneksi', authType]]} />{mode === 'network' && <p>Akun akan disiapkan di RADIUS. Pengaturan autentikasi pada router lama tetap perlu dipindahkan secara terpisah.</p>}</div> : undefined}
    reviewFooter={<><Button disabled={busy} onClick={() => setReview(false)}>Sebelumnya</Button><Button disabled={busy} onClick={onClose}>Batal</Button><Button variant="primary" disabled={busy} onClick={() => void save()}>{busy ? 'Memproses…' : title}</Button></>}>
    <ResourceStep step="settings">{error && <p role="alert">{error}</p>}{mode === 'billing' ? <><TextField label="Tanggal tagihan pertama" required type="date" min={localToday()} value={date} onChange={(_, data) => setDate(data.value)} /><TextField label="Tanggal tagih setiap bulan" type="number" min={1} max={31} value={day} onChange={(_, data) => setDay(data.value)} /><p className="muted">Pilih periode yang belum ditagih di sistem sebelumnya.</p></> : <>
      <SelectField label="BRAS" required value={nasId} onChange={(_, data) => setNasId(data.value)}><option value="">Pilih BRAS</option>{routers.map(router => <option key={router.id} value={router.id}>{router.name}</option>)}</SelectField>
      {error && <div><Button onClick={() => setLoadAttempt(value => value + 1)}>Muat ulang BRAS</Button></div>}
      <TextField label="Username" required value={username} onChange={(_, data) => setUsername(data.value)} />
      <SelectField label="Jenis koneksi" value={authType} onChange={(_, data) => setAuthType(data.value as ServiceType)}><option value="PPPOE">PPPoE</option><option value="HOTSPOT">Hotspot</option><option value="DHCP">DHCP</option><option value="STATIC">IP statis</option></SelectField>
      {['PPPOE', 'HOTSPOT'].includes(authType) && <TextField label={migration.hasPassword ? 'Password pengganti (opsional)' : 'Password'} required={!migration.hasPassword} type="password" autoComplete="new-password" value={password} onChange={(_, data) => setPassword(data.value)} />}
      {['STATIC', 'DHCP'].includes(authType) && <TextField label="Alamat IP" required={authType === 'STATIC'} value={ip} onChange={(_, data) => setIp(data.value)} />}
    </>}</ResourceStep>
  </ResourceForm>
}
