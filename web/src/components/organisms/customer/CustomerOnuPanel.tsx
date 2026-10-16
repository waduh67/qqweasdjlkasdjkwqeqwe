import { useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { getCustomerMigration, linkMigrationOnu, type MigrationView } from '@/api/customerMigration'
import type { DiscoveredOnuView } from '@/api/monitoring'
import type { OnuView } from '@/api/network'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { CreationSummary } from '@/components/organisms/CreationReview'
import { ResourceForm, useResourceReviewLock } from '@/components/organisms/ResourceForm'
import { ResourceStep } from '@/components/organisms/ResourceStep'
import { MigrationOnuPicker } from '@/pages/onboarding/MigrationOnuPicker'

const status: Record<string, string> = { PENDING: 'Menunggu pembacaan', ONLINE: 'Online', OFFLINE: 'Offline', LOS: 'LOS', DISMANTLED: 'Dilepas' }

export function CustomerOnuPanel({ customerId, onus, onChanged }: { customerId: string; onus: OnuView[]; onChanged: () => void }) {
  const [migration, setMigration] = useState<MigrationView | null>(null)
  const [open, setOpen] = useState(false)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const { can } = useCan(), { readOnly } = useAuth()
  useEffect(() => {
    let active = true
    setMigration(null); setOpen(false); setError('')
    void getCustomerMigration(customerId).then(value => { if (active) setMigration(value) })
      .catch(() => { if (active) setError('Data migrasi belum berhasil dimuat.') })
    return () => { active = false }
  }, [customerId, attempt])
  return <section className="customer-section" aria-label="Pemantauan ONU">
    <h2>Pemantauan ONU</h2>
    {error && <div role="alert"><p>{error}</p><Button onClick={() => setAttempt(value => value + 1)}>Coba lagi</Button></div>}
    {migration && can('customer.onu.assign') && can('monitoring.provisioning.view') && <div className="customer-actions">
      <Button variant="subtle" disabled={readOnly} onClick={() => setOpen(true)}>Hubungkan ONU terpasang</Button>
    </div>}
    <DataTable rows={onus} rowKey={row => row.id} columns={[
      { key: 'serial', header: 'Nomor seri', minWidth: 180, cell: row => row.serialNumber },
      { key: 'status', header: 'Status', cell: row => status[row.status] ?? row.status },
      { key: 'olt', header: 'OLT', cell: row => migration?.onus?.find(item => item.onuId === row.id)?.oltCode ?? '—' },
      { key: 'pon', header: 'PON', cell: row => migration?.onus?.find(item => item.onuId === row.id)?.ponPortLabel ?? '—' },
      { key: 'odp', header: 'ODP', cell: row => row.odpCode ? `${row.odpCode} · ${row.odpPortNumber}` : '—' },
      { key: 'rx', header: 'Rx pemasangan (dBm)', cell: row => row.installRxPowerDbm ?? '—' },
      { key: 'source', header: 'Pencatatan', cell: row => migration?.onus?.some(item => item.onuId === row.id) ? 'Migrasi pelanggan' : '—' },
    ]} empty={<p>Belum ada ONU yang terhubung.</p>} />
    {open && migration && <LinkOnu customerId={customerId} onClose={() => setOpen(false)} onSaved={items => {
      setMigration({ ...migration, onus: items }); setOpen(false); onChanged()
    }} />}
  </section>
}

function LinkOnu({ customerId, onClose, onSaved }: { customerId: string; onClose: () => void; onSaved: (onus: NonNullable<MigrationView['onus']>) => void }) {
  const [onu, setOnu] = useState<DiscoveredOnuView | null>(null)
  const [review, setReview] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  async function save() {
    if (!onu) return
    setBusy(true); setError('')
    try { onSaved(await linkMigrationOnu(customerId, onu.serialNumber)) }
    catch (e) { setUncertain(!(e instanceof ApiError)); setError(e instanceof ApiError ? e.message : 'Hasil permintaan belum diketahui. Coba lagi untuk memeriksa.') }
    finally { setBusy(false) }
  }
  return <ResourceForm title="Hubungkan ONU terpasang" onClose={onClose} busy={busy}
    steps={[{ key: 'device', label: 'Perangkat', validate: () => onu ? null : 'Pilih ONU terpasang.' }]}
    onBack={() => { if (!uncertain) setReview(false) }} onReview={() => setReview(true)}
    review={review ? <div className="stack"><ReviewLock locked={uncertain} />{error && <p role="alert">{error}</p>}<CreationSummary rows={[
      ['Nomor seri', onu?.serialNumber], ['OLT', onu?.oltCode], ['PON', onu?.ponPortLabel], ['Pencatatan', 'Perangkat terpasang sebelum migrasi'],
    ]} /><p className="muted">Asal dan kepemilikan aset gudang belum dicatat.</p></div> : undefined}
    reviewFooter={<><Button disabled={busy || uncertain} onClick={() => setReview(false)}>Sebelumnya</Button><Button disabled={busy || uncertain} onClick={onClose}>Batal</Button><Button variant="primary" disabled={busy || !onu} onClick={() => void save()}>{busy ? 'Menghubungkan…' : uncertain ? 'Coba lagi' : 'Hubungkan'}</Button></>}>
    <ResourceStep step="device"><MigrationOnuPicker value={onu} onChange={setOnu} required />
      <p className="muted">Pilih ONU yang sudah digunakan pelanggan ini.</p>
    </ResourceStep>
  </ResourceForm>
}
function ReviewLock({ locked }: { locked: boolean }) { useResourceReviewLock(locked); return null }
