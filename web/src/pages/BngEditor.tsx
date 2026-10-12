import { useState } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { createNas, updateNas, NAS_REACHABILITY_LABEL, NAS_REACHABILITY_HINT, NAS_VENDOR_LABEL, NAS_VENDORS, type NasView } from '@/api/bng'
import type { Area } from '@/api/types'
import { Button, SelectField, TextField } from '@/components/atoms'
import { CreationSummary } from '@/components/organisms/CreationReview'
import { ResourceWizard, type ResourceStep } from '@/components/organisms/ResourceWizard'
import { radiusTargetFor, selfAddressWarning, sessionControlRoute } from '@/utils/radiusTarget'
import { BngConfiguration } from './BngConfiguration'
import { BngRadiusStatus, type BngRadiusState } from './BngRadiusStatus'

function initialDraft(nas: NasView | null) {
  return {
    name: nas?.name ?? '', vendor: nas?.vendor ?? 'MIKROTIK', address: nas?.address ?? '',
    nasIdentifier: nas?.nasIdentifier ?? '', coaSecret: '', enabled: nas?.enabled ?? true,
    apiUsername: nas?.apiUsername ?? '', apiSecret: '', apiPort: nas?.apiPort?.toString() ?? '',
    apiUseTls: nas?.apiUseTls ?? true, areaIds: nas?.areaIds ?? [], collectorId: nas?.collectorId ?? null,
  }
}

function randomSecret() {
  const bytes = crypto.getRandomValues(new Uint8Array(18))
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function BngEditor({ nas, radius, onReloadRadius, areas, canViewAreas, areasFailed, onReloadAreas, onClose, onSaved }: {
  nas: NasView | null; radius: BngRadiusState; onReloadRadius: () => void; areas: Area[]; canViewAreas: boolean
  areasFailed: boolean; onReloadAreas: () => void; onClose: () => void; onSaved: () => void
}) {
  const [initial] = useState(() => initialDraft(nas))
  const [draft, setDraft] = useState(initial)
  const [reveal, setReveal] = useState(false), [configuration, setConfiguration] = useState(false)
  const [areaSearch, setAreaSearch] = useState('')
  const endpoint = radius.status === 'ready' ? radius.endpoint : null
  const patch = (next: Partial<typeof draft>) => setDraft(current => ({ ...current, ...next }))
  const target = endpoint ? radiusTargetFor(endpoint, draft.address) : null
  const warning = selfAddressWarning(endpoint, draft.address)
  const route = sessionControlRoute(endpoint, draft.address, !!draft.collectorId)
  const selectedAreas = draft.areaIds.map(id => areas.find(area => area.id === id)?.name ?? 'Area tersimpan')
  const steps: ResourceStep[] = [
    { key: 'basics', label: 'Dasar', validate: () => draft.name.trim().length < 2 ? 'Isi nama BRAS dengan 2–80 karakter.' : null, content: <>
      <h2>Identitas router</h2>
      <TextField label="Nama BRAS" required minLength={2} maxLength={80} value={draft.name} onChange={(_, data) => patch({ name: data.value })} placeholder="BRAS-TEBET-01" />
      <SelectField label="Vendor" value={draft.vendor} onChange={(_, data) => patch({ vendor: data.value as NasView['vendor'] })}>
        {NAS_VENDORS.map(vendor => <option key={vendor} value={vendor}>{NAS_VENDOR_LABEL[vendor]}</option>)}
      </SelectField>
      <TextField label="Alamat manajemen" value={draft.address} maxLength={255} onChange={(_, data) => patch({ address: data.value })}
        placeholder="10.8.0.3" hint="Gunakan alamat VPN jika router terhubung melalui VPN." validationState={warning ? 'warning' : undefined} validationMessage={warning ?? undefined} />
      <Checkbox className="resource-choice-control" label="Aktifkan BRAS" checked={draft.enabled} onChange={(_, data) => patch({ enabled: data.checked === true })} />
    </> },
    { key: 'radius', label: 'RADIUS', content: <>
      <h2>Autentikasi pelanggan</h2>
      <TextField label="NAS-Identifier" maxLength={128} value={draft.nasIdentifier} onChange={(_, data) => patch({ nasIdentifier: data.value })} hint="Opsional. Samakan dengan identitas NAS pada router." />
      <TextField label="Shared secret RADIUS" type={reveal ? 'text' : 'password'} maxLength={255} value={draft.coaSecret} onChange={(_, data) => patch({ coaSecret: data.value })}
        autoComplete="new-password" placeholder={nas?.hasCoaSecret ? 'Kosongkan untuk mempertahankan secret' : 'Masukkan shared secret'}
        hint="Gunakan secret yang sama pada router dan server RADIUS." />
      <div className="network-secret-actions"><Button onClick={() => { patch({ coaSecret: randomSecret() }); setReveal(true) }}>Buat secret acak</Button>
        <Button disabled={!draft.coaSecret} onClick={() => setReveal(value => !value)}>{reveal ? 'Sembunyikan secret' : 'Tampilkan secret'}</Button></div>
      <BngRadiusStatus state={radius} onRetry={onReloadRadius} />
      {endpoint && <><CreationSummary rows={[
        ['Server RADIUS', target?.host ?? 'Belum diatur'],
        ['Kendali sesi', NAS_REACHABILITY_LABEL[route]],
      ]} />
      <p className="network-note">{NAS_REACHABILITY_HINT[route]}</p></>}
      {draft.vendor === 'MIKROTIK' && <div><Button onClick={() => setConfiguration(true)}>Lihat konfigurasi RouterOS</Button></div>}
    </> },
    ...(draft.vendor === 'MIKROTIK' ? [{ key: 'api', label: 'Akses RouterOS', validate: () => draft.apiPort && (!/^\d+$/.test(draft.apiPort) || Number(draft.apiPort) < 1 || Number(draft.apiPort) > 65535) ? 'Isi port antara 1 dan 65535.' : null, content: <>
      <h2>Akses pengelolaan router</h2>
      <p className="network-note">Opsional. Digunakan untuk mengelola akun PPPoE melalui RouterOS REST API.</p>
      <TextField label="Nama pengguna API" maxLength={128} autoComplete="off" value={draft.apiUsername} onChange={(_, data) => patch({ apiUsername: data.value })} />
      <TextField label="Kata sandi API" maxLength={255} type="password" autoComplete="new-password" value={draft.apiSecret} onChange={(_, data) => patch({ apiSecret: data.value })}
        placeholder={nas?.hasApiSecret ? 'Kosongkan untuk mempertahankan kata sandi' : 'Masukkan kata sandi'} />
      <TextField label="Port API" type="number" min={1} max={65535} step={1} value={draft.apiPort} onChange={(_, data) => patch({ apiPort: data.value })} placeholder={draft.apiUseTls ? '443' : '80'} />
      <Checkbox className="resource-choice-control" label="Gunakan HTTPS" checked={draft.apiUseTls} onChange={(_, data) => patch({ apiUseTls: data.checked === true })} />
    </> }] : []),
    { key: 'areas', label: 'Cakupan area', content: <>
      <h2>Area layanan</h2>
      <p className="network-note">BRAS ini akan dipilih otomatis untuk pelanggan di area yang ditetapkan. Setiap area dapat terhubung ke satu BRAS.</p>
      {!canViewAreas ? <p>Akses area tidak tersedia untuk akun Anda.</p> : areasFailed ? <div className="network-error" role="alert"><p>Daftar area gagal dimuat.</p><Button onClick={onReloadAreas}>Coba lagi</Button></div>
        : areas.length === 0 ? <p>Belum ada area yang tersedia.</p> : <>
          <TextField label="Cari area" value={areaSearch} onChange={(_, data) => setAreaSearch(data.value)} />
          <div role="group" aria-label="Area layanan" className="network-area-options">{areas.filter(area => `${area.name} ${area.code}`.toLowerCase().includes(areaSearch.trim().toLowerCase())).map(area =>
            <Checkbox key={area.id} label={area.name} checked={draft.areaIds.includes(area.id)} onChange={(_, data) => patch({ areaIds: data.checked ? [...draft.areaIds, area.id] : draft.areaIds.filter(id => id !== area.id) })} />)}</div>
          <p className="network-note">{draft.areaIds.length} area dipilih</p>
        </>}
    </> },
  ]
  return <>
    <ResourceWizard title={nas ? 'Ubah BRAS' : 'Tambah BRAS'} steps={steps} editing={!!nas} dirty={JSON.stringify(initial) !== JSON.stringify(draft)} onClose={onClose}
      onSave={async () => {
        const body = { ...draft, name: draft.name.trim(), address: draft.address.trim() || null, nasIdentifier: draft.nasIdentifier.trim() || null,
          coaSecret: draft.coaSecret || null, apiUsername: draft.apiUsername.trim() || null, apiSecret: draft.apiSecret || null, apiPort: draft.apiPort ? Number(draft.apiPort) : null }
        await (nas ? updateNas(nas.id, body) : createNas(body))
        onSaved()
      }} summary={<>
        <section><h2>Dasar</h2><CreationSummary rows={[
          ['Nama BRAS', draft.name], ['Vendor', NAS_VENDOR_LABEL[draft.vendor]], ['Alamat manajemen', draft.address], ['Status', draft.enabled ? 'Aktif' : 'Nonaktif'],
        ]} /></section>
        <section><h2>RADIUS</h2><BngRadiusStatus state={radius} onRetry={onReloadRadius} /><CreationSummary rows={[
          ['NAS-Identifier', draft.nasIdentifier], ['Shared secret', draft.coaSecret ? 'Secret baru' : nas?.hasCoaSecret ? 'Tidak diubah' : 'Belum diisi'],
          ...(endpoint ? [['Server RADIUS', target?.host ?? 'Belum diatur'], ['Kendali sesi', NAS_REACHABILITY_LABEL[route]]] as [string, string][] : []),
        ]} /></section>
        {draft.vendor === 'MIKROTIK' && <section><h2>Akses RouterOS</h2><CreationSummary rows={[
          ['Nama pengguna API', draft.apiUsername], ['Kata sandi API', draft.apiSecret ? 'Kata sandi baru' : nas?.hasApiSecret ? 'Tidak diubah' : 'Belum diisi'],
          ['Port API', draft.apiPort || (draft.apiUseTls ? '443 (bawaan)' : '80 (bawaan)')], ['HTTPS', draft.apiUseTls ? 'Ya' : 'Tidak'],
        ]} /></section>}
        <section><h2>Cakupan area</h2><CreationSummary rows={[[ 'Area layanan', selectedAreas.join(', ') || 'Belum ditetapkan' ]]} /></section>
      </>}>
      {configuration && <BngConfiguration radius={radius} onReloadRadius={onReloadRadius} secret={draft.coaSecret} host={target?.host ?? null} onClose={() => setConfiguration(false)} />}
    </ResourceWizard>
  </>
}
