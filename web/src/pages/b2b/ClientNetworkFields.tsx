import { useCallback, useState } from 'react'
import { networkInventory } from '@/api/networkInventory'
import type { NetworkInput, PublicIp } from '@/api/networkModels'
import { Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { ResourceStep } from '@/components/organisms/ResourceStep'
import { VendorField } from '../network-inventory/shared'
import { B2BPagination, LoadError, useB2BPage } from './shared'

export function ClientNetworkFields({ value, ips, onChange, onIpsChange, writable, passwordSet }: {
  value: NetworkInput; ips: readonly PublicIp[]; onChange: (v: NetworkInput) => void; onIpsChange: (ips: readonly PublicIp[]) => void; writable: boolean; passwordSet: boolean
}) {
  const d = value.details
  const update = <K extends keyof typeof d>(key: K, v: typeof d[K]) => onChange({ ...value, details: { ...d, [key]: v } })
  const [q, setQ] = useState(''), [page, setPage] = useState(0)
  const [dnsText, setDnsText] = useState(d.dns.join(', '))
  const load = useCallback(() => writable ? networkInventory.ips({ q, page, status: 'AVAILABLE' }) : Promise.resolve({ content: [], page: 0, size: 20, totalElements: 0 }), [q, page, writable])
  const available = useB2BPage(load)
  const choose = (ip: PublicIp) => { if (!ips.some(v => v.id === ip.id) && ips.length < 64) onIpsChange([...ips, ip]) }
  return <>
    <ResourceStep step="network">
      <h3>PIC & koneksi</h3>
      <TextField label="Nama PIC" value={d.picName} maxLength={200} disabled={!writable} onChange={(_, v) => update('picName', v.value)} />
      <TextField label="No. HP PIC" type="tel" value={d.phone} maxLength={50} disabled={!writable} onChange={(_, v) => update('phone', v.value)} />
      <TextField label="Bandwidth mainlink (Mbps)" type="number" min={1} max={1000000} value={d.mainBandwidthMbps === null ? '' : String(d.mainBandwidthMbps)} disabled={!writable} onChange={(_, v) => update('mainBandwidthMbps', v.value ? Number(v.value) : null)} />
      <SelectField label="Infrastruktur mainlink" value={d.mainInfrastructure} disabled={!writable} onChange={(_, v) => { if (v.value === 'OWN' || v.value === 'PARTNER') onChange({ ...value, details: { ...d, mainInfrastructure: v.value, mainVendorId: v.value === 'OWN' ? null : d.mainVendorId } }) }}><option value="OWN">Infrastruktur sendiri</option><option value="PARTNER">Infrastruktur mitra</option></SelectField>
      {d.mainInfrastructure === 'PARTNER' && <VendorField label="Vendor mainlink" value={d.mainVendorId} activeOnly required disabled={!writable} onChange={v => update('mainVendorId', v)} />}
      <TextField label="Last mile / layanan mainlink" hint="Contoh: Metro Telkom, fiber sendiri." maxLength={200} value={d.mainLastMile} disabled={!writable} onChange={(_, v) => update('mainLastMile', v.value)} />
      <TextField label="ID pelanggan mainlink" maxLength={200} value={d.mainCustomerId} disabled={!writable} onChange={(_, v) => update('mainCustomerId', v.value)} />
      <TextField label="Bandwidth backup (Mbps)" type="number" min={1} max={1000000} value={d.backupBandwidthMbps === null ? '' : String(d.backupBandwidthMbps)} disabled={!writable} onChange={(_, v) => update('backupBandwidthMbps', v.value ? Number(v.value) : null)} />
      <VendorField label="Vendor backup" value={d.backupVendorId} activeOnly disabled={!writable} onChange={v => update('backupVendorId', v)} />
      <TextField label="ID pelanggan backup" maxLength={200} value={d.backupCustomerId} disabled={!writable} onChange={(_, v) => update('backupCustomerId', v.value)} />
    </ResourceStep>
    <ResourceStep step="technical">
      <h3>Alokasi IP public</h3><p className="muted">Daftarkan IP di Manajemen IP terlebih dahulu. IP terpilih langsung dialokasikan saat client disimpan. Maksimal 64 IP.</p>
      <DataTable presentation="warehouse" aria-label="IP terpilih" rows={[...ips]} rowKey={ip => ip.id} empty="Belum ada IP terpilih." columns={[{ key: 'address', header: 'IP public', cell: ip => ip.address }, { key: 'vendor', header: 'Vendor', cell: ip => ip.vendorName || 'Infrastruktur sendiri' }, ...(writable ? [{ key: 'remove', header: 'Alokasi', cell: (ip: PublicIp) => <Button aria-label={'Lepas IP ' + ip.address} onClick={() => onIpsChange(ips.filter(v => v.id !== ip.id))}>Lepas</Button> }] : [])]} />
      {writable && <><TextField label="Cari IP tersedia" value={q} maxLength={200} onChange={(_, v) => { setQ(v.value); setPage(0) }} />
        <LoadError error={available.error} reload={available.reload} />
        <DataTable presentation="warehouse" aria-label="IP tersedia" loading={available.loading} rows={available.data?.content ?? []} rowKey={ip => ip.id} empty={available.error ? <></> : 'Tidak ada IP tersedia yang sesuai.'} columns={[{ key: 'address', header: 'IP public', cell: ip => ip.address }, { key: 'vendor', header: 'Vendor', cell: ip => ip.vendorName || 'Infrastruktur sendiri' }, { key: 'select', header: 'Alokasi', cell: ip => <Button aria-pressed={ips.some(v => v.id === ip.id)} disabled={ips.length >= 64 && !ips.some(v => v.id === ip.id)} onClick={() => choose(ip)}>{ips.some(v => v.id === ip.id) ? 'Terpilih' : 'Pilih IP'}</Button> }]} />
        <B2BPagination data={available.data} onChange={setPage} /></>}
      <TextField label="Subnet mask" placeholder="255.255.255.248" value={d.subnet} maxLength={15} disabled={!writable} onChange={(_, v) => update('subnet', v.value)} />
      <TextField label="Gateway" placeholder="157.15.122.99" value={d.gateway} maxLength={15} disabled={!writable} onChange={(_, v) => update('gateway', v.value)} />
      <TextField label="DNS" hint="Pisahkan beberapa IPv4 dengan koma. Maksimal 8 DNS." value={dnsText} maxLength={150} disabled={!writable} onChange={(_, v) => { setDnsText(v.value); update('dns', v.value.split(',').map(s => s.trim()).filter(Boolean)) }} />
    </ResourceStep>
    <ResourceStep step="tunnel">
      <h3>Tunneling & catatan</h3>
      <TextField label="Dial tunnel" value={d.tunnelDial} maxLength={15} disabled={!writable} onChange={(_, v) => update('tunnelDial', v.value)} />
      <TextField label="Username tunnel" value={d.tunnelUsername} maxLength={200} autoComplete="off" disabled={!writable} onChange={(_, v) => update('tunnelUsername', v.value)} />
      <p className="muted">{passwordSet ? 'Password tunnel tersimpan dan disembunyikan. Kosongkan isian untuk mempertahankannya.' : 'Password tunnel belum diisi.'}</p>
      {writable && <><TextField label="Password tunnel baru" type="password" autoComplete="new-password" maxLength={500} disabled={value.clearTunnelPassword} value={value.tunnelPassword ?? ''} onChange={(_, v) => onChange({ ...value, tunnelPassword: v.value || null })} />
        <SelectField label="Password tersimpan" value={value.clearTunnelPassword ? 'clear' : 'keep'} onChange={(_, v) => onChange({ ...value, clearTunnelPassword: v.value === 'clear', tunnelPassword: v.value === 'clear' ? null : value.tunnelPassword })}><option value="keep">Pertahankan / ganti dengan isian baru</option><option value="clear">Hapus password tersimpan</option></SelectField></>}
      <TextField label="Remote address tunnel" value={d.tunnelRemoteAddress} maxLength={15} disabled={!writable} onChange={(_, v) => update('tunnelRemoteAddress', v.value)} />
      <TextField label="Local address tunnel" value={d.tunnelLocalAddress} maxLength={15} disabled={!writable} onChange={(_, v) => update('tunnelLocalAddress', v.value)} />
      <TextareaField label="Catatan jaringan" value={d.notes} maxLength={5000} disabled={!writable} onChange={(_, v) => update('notes', v.value)} />
    </ResourceStep>
  </>
}
