import { useCallback, useState } from 'react'
import { networkInventory } from '@/api/networkInventory'
import type { NetworkDetails, PublicIp } from '@/api/networkModels'
import { Button, SelectField, TextField } from '@/components/atoms'
import { Modal } from '@/components/molecules/Modal'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { B2BPagination, errorText, LoadError, useB2BPage } from '../b2b/shared'

export function VendorField({ label, value, onChange, activeOnly = false, required = false, disabled = false, emptyLabel }: {
  label: string; value: string | null; onChange: (id: string | null) => void; activeOnly?: boolean; required?: boolean; disabled?: boolean; emptyLabel?: string
}) {
  const [q, setQ] = useState(''), [page, setPage] = useState(0)
  const load = useCallback(() => networkInventory.vendors({ q, page, ...(activeOnly ? { active: true } : {}) }), [q, page, activeOnly])
  const vendors = useB2BPage(load)
  const selectedLoad = useCallback(() => value ? networkInventory.vendor(value) : Promise.resolve(null), [value])
  const selected = useWarehouseQuery(selectedLoad)
  const chosen = selected.state.status === 'ready' ? selected.state.data : null
  const options = vendors.data?.content ?? []
  return <div className="stack">
    {!disabled && <TextField label={'Cari ' + label.toLowerCase()} value={q} maxLength={200} onChange={(_, d) => { setQ(d.value); setPage(0) }} />}
    <SelectField label={label} required={required} value={value ?? ''} disabled={disabled} onChange={(_, d) => onChange(d.value || null)}>
      <option value="">{emptyLabel ?? (required ? 'Pilih vendor' : 'Tanpa vendor')}</option>
      {value && !options.some(v => v.id === value) && <option value={value}>{chosen?.name ?? 'Vendor tersimpan'}{chosen && !chosen.active ? ' (nonaktif)' : ''}</option>}
      {options.map(v => <option key={v.id} value={v.id}>{v.name}{v.active ? '' : ' (nonaktif)'}</option>)}
    </SelectField>
    <LoadError error={vendors.error} reload={vendors.reload} />
    <LoadError error={selected.state.status === 'error' ? errorText(selected.state.error) : ''} reload={selected.reload} />
    {!disabled && <B2BPagination data={vendors.data} onChange={setPage} />}
  </div>
}

export function NetworkSummary({ details: d, ips, passwordState }: { details: NetworkDetails; ips: readonly PublicIp[]; passwordState: string }) {
  return <div className="stack">
    <dl className="resource-review-summary">
      <dt>Nama PIC</dt><dd>{d.picName || '—'}</dd><dt>No. HP</dt><dd>{d.phone || '—'}</dd>
      <dt>Mainlink</dt><dd>{d.mainBandwidthMbps ? d.mainBandwidthMbps + ' Mbps' : '—'} · {d.mainInfrastructure === 'OWN' ? 'Infrastruktur sendiri' : 'Infrastruktur mitra'} · {d.mainLastMile || 'Last mile belum diisi'}</dd>
      <dt>Vendor mainlink</dt><dd>{d.mainVendorId ? <VendorName id={d.mainVendorId} /> : '—'}</dd><dt>ID pelanggan mainlink</dt><dd>{d.mainCustomerId || '—'}</dd>
      <dt>Backup</dt><dd>{d.backupBandwidthMbps ? d.backupBandwidthMbps + ' Mbps' : '—'}</dd>
      <dt>Vendor backup</dt><dd>{d.backupVendorId ? <VendorName id={d.backupVendorId} /> : '—'}</dd><dt>ID pelanggan backup</dt><dd>{d.backupCustomerId || '—'}</dd>
      <dt>IP public</dt><dd>{ips.length ? ips.map(ip => ip.address).join(', ') : 'Belum dialokasikan'}</dd>
      <dt>Subnet</dt><dd>{d.subnet || '—'}</dd><dt>Gateway</dt><dd>{d.gateway || '—'}</dd><dt>DNS</dt><dd>{d.dns.join(', ') || '—'}</dd>
      <dt>Dial tunnel</dt><dd>{d.tunnelDial || '—'}</dd><dt>Username tunnel</dt><dd>{d.tunnelUsername || '—'}</dd><dt>Password tunnel</dt><dd>{passwordState}</dd>
      <dt>Remote address</dt><dd>{d.tunnelRemoteAddress || '—'}</dd><dt>Local address</dt><dd>{d.tunnelLocalAddress || '—'}</dd><dt>Catatan</dt><dd style={{ whiteSpace: 'pre-wrap' }}>{d.notes || '—'}</dd>
    </dl>
  </div>
}
export function VendorName({ id }: { id: string }) {
  const load = useCallback(() => networkInventory.vendor(id), [id]), { state, reload } = useWarehouseQuery(load)
  if (state.status === 'loading') return <span role="status">Memuat vendor…</span>
  if (state.status === 'error') return <LoadError error={errorText(state.error)} reload={reload} />
  return <span>{state.data.name}{state.data.active ? '' : ' (nonaktif)'}</span>
}
export function ClientNetworkDetail({ clientId, name, onClose }: { clientId: string; name: string; onClose: () => void }) {
  const load = useCallback(() => networkInventory.client(clientId), [clientId]), { state, reload } = useWarehouseQuery(load)
  return <Modal title={'Jaringan · ' + name} onClose={onClose} footer={<Button onClick={onClose}>Tutup</Button>}>
    {state.status === 'loading' ? <p role="status">Memuat data jaringan…</p> : state.status === 'error' ? <LoadError error={errorText(state.error)} reload={reload} /> : state.data ?
      <NetworkSummary details={state.data.details} ips={state.data.publicIps} passwordState={state.data.tunnelPasswordSet ? 'Tersimpan (disembunyikan)' : 'Belum diisi'} /> : <p>Data jaringan belum diisi.</p>}
  </Modal>
}
