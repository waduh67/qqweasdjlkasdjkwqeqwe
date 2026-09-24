import { useCallback, useState, type FormEvent } from 'react'
import { getPolicyDetails, type PolicyChoice, type PolicyDetails, type PolicyOperation } from '@/api/warehouse/policy'
import { createDelegation, delegationCandidates, type DelegationInput, type WarehouseDelegation } from '@/api/warehouse/settings'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { approvalOperationLabels } from './approvalPresentation'
import { locationLabel } from './receiptChoices'

export function WarehouseDelegationForm({ onDone, onClose }: { onDone: () => void; onClose: () => void }) {
  const result = useWarehouseQuery(getPolicyDetails)
  return <section className="card stack" aria-label="Form delegasi pemeriksa"><Button onClick={onClose}>Tutup form delegasi</Button><WarehouseState {...result}>{settings => settings.current ?
    <DelegationForm settings={settings} onDone={onDone} reload={result.reload} /> : <p>Simpan kebijakan dengan pemeriksa independen sebelum membuat delegasi.</p>}</WarehouseState></section>
}
function DelegationForm({ settings, onDone, reload }: { settings: PolicyDetails; onDone: () => void; reload: () => void }) {
  const [locationId, setLocationId] = useState(''), [operation, setOperation] = useState<PolicyOperation>(settings.current!.rules[0].operation), [sourceRoleId, setSourceRoleId] = useState('')
  const [approver, setApprover] = useState<PolicyChoice | null>(null), [delegate, setDelegate] = useState<PolicyChoice | null>(null)
  const [until, setUntil] = useState(''), [error, setError] = useState<string | null>(null)
  const [review, setReview] = useState<{ command: WarehouseCommand<WarehouseDelegation>; input: DelegationInput; source: string; target: string } | null>(null)
  const tiers = settings.current!.rules.find(rule => rule.operation === operation)!.tiers
  const roles = settings.references.roles.filter(role => tiers.some(tier => tier.roleIds.includes(role.id)))
  const loadApprovers = useCallback((query: string, page: number) => locationId ? delegationCandidates({ locationId, operation, kind: 'APPROVER', sourceRoleId: sourceRoleId || undefined, query, page }) : Promise.resolve({ items: [], page: 0, size: 25, totalElements: 0 }), [locationId, operation, sourceRoleId])
  const loadDelegates = useCallback((query: string, page: number) => locationId && approver ? delegationCandidates({ locationId, operation, kind: 'DELEGATE', approverId: approver.id, sourceRoleId: sourceRoleId || undefined, query, page }) : Promise.resolve({ items: [], page: 0, size: 25, totalElements: 0 }), [locationId, operation, sourceRoleId, approver])
  function reset() { setApprover(null); setDelegate(null) }
  function submit(event: FormEvent) {
    event.preventDefault()
    const validUntil = Date.parse(until), now = Date.now()
    if (!locationId || !approver || !delegate || approver.id === delegate.id) { setError('Pilih lokasi, pemeriksa asal, dan penerima delegasi yang berbeda.'); return }
    if (!Number.isFinite(validUntil) || validUntil <= now || validUntil > now + 30 * 86400000) { setError('Batas delegasi harus setelah sekarang dan maksimal 30 hari.'); return }
    const input: DelegationInput = { expectedRevision: 0, approverId: approver.id, delegateId: delegate.id, sourceRoleId: sourceRoleId || null, locationId, operation, validUntil: new Date(validUntil).toISOString() }
    setReview({ command: createDelegation(input), input, source: approver.name, target: delegate.name }); setError(null)
  }
  return <><form className="stack" onSubmit={submit}><h3>Delegasi pemeriksa baru</h3><p>Kebijakan versi {settings.current!.revision}. Delegasi terbatas pada jenis persetujuan dan lokasi ini. Penerima tetap memerlukan izin dan cakupan sendiri; rantai delegasi tidak diizinkan.</p>
    <SelectField label="Lokasi delegasi" required value={locationId} onChange={(_, value) => { setLocationId(value.value); reset() }}><option value="">Pilih lokasi</option>{settings.references.locations.map(row => <option value={row.id} key={row.id}>{locationLabel(row)}</option>)}</SelectField>
    <SelectField label="Jenis persetujuan delegasi" value={operation} onChange={(_, value) => { setOperation(value.value as PolicyOperation); setSourceRoleId(''); reset() }}>{settings.current!.rules.map(rule => <option key={rule.operation} value={rule.operation}>{approvalOperationLabels[rule.operation]}</option>)}</SelectField>
    <SelectField label="Sumber kewenangan delegasi" value={sourceRoleId} onChange={(_, value) => { setSourceRoleId(value.value); reset() }}><option value="">Pengguna yang ditunjuk langsung</option>{roles.map(role => <option key={role.id} value={role.id}>Role {role.name}</option>)}</SelectField>
    <WarehousePicker key={`source:${locationId}:${operation}:${sourceRoleId}`} label="Pemeriksa asal" load={loadApprovers} value={approver} onChange={value => { setApprover(value); setDelegate(null) }} name={row => row.name} disabled={!locationId} />
    <WarehousePicker key={`delegate:${locationId}:${operation}:${sourceRoleId}:${approver?.id}`} label="Penerima delegasi" load={loadDelegates} value={delegate} onChange={setDelegate} name={row => row.name} disabled={!approver} />
    <TextField label="Delegasi berlaku sampai" type="datetime-local" required value={until} onChange={(_, value) => setUntil(value.value)} hint="Waktu lokal perangkat, maksimal 30 hari. Izin diperiksa lagi saat keputusan dibuat." />
    {error && <p role="alert">{error}</p>}<Button type="submit" variant="primary">Tinjau delegasi</Button>
  </form>{review && <WarehouseCommandDialog title="Konfirmasi delegasi pemeriksa" command={review.command} confirmLabel="Simpan delegasi" onClose={() => setReview(null)} onDone={onDone} onReload={reload}
    summary={<><p>{review.source} → {review.target}</p><p>{locationLabel(settings.references.locations.find(row => row.id === review.input.locationId)!)} · {approvalOperationLabels[review.input.operation]}</p>
      <p>Sumber: {review.input.sourceRoleId ? `Role ${roles.find(role => role.id === review.input.sourceRoleId)?.name}` : 'Penunjukan pengguna langsung'}</p><p>Berlaku sampai {new Date(review.input.validUntil).toLocaleString('id-ID')}.</p><p>Delegasi tidak mengizinkan persetujuan sendiri atau pengganti yang terlibat dalam dokumen.</p></>} />}</>
}
