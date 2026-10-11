import { useState } from 'react'
import { Blade } from '@/components/organisms/Blade'
import { CreationSummary, useCreationReview } from '@/components/organisms/CreationReview'
import { ResourceStep } from '@/components/organisms/ResourceStep'
import { CommandBar } from '@/components/molecules'
import { Text } from '@fluentui/react-components'
import { SERVICE_TYPE_LABEL } from '@/api/catalog'
import { oneOf } from '@/api/warehouse/codec'
import { Button, SelectField, TextField, TextareaField } from '@/components/atoms'
import { IconPlus } from '@/components/atoms/icons'
import { LocationPicker } from '@/components/organisms'
import { MultiCombobox } from '@/components/molecules'
import type { useExpressPsb } from './useExpressPsb'

export function ExpressPsbFields({ draft, set, areas, nasByArea, changeArea, plans, planId, changePlan,
  authType, setAuthType, availableTypes, macBased, secret, showSecret, setSecret, setShowSecret,
  randomSecret, nasId, setNasId, nasList, reference, workflowMode, technicians, submit, saving, invalid, resetDraft
}: ReturnType<typeof useExpressPsb>) {
  const [open, setOpen] = useState(false), [error, setError] = useState('')
  const creation = useCreationReview(open)
  const close = () => { setOpen(false); setError(''); resetDraft() }
  async function prepare() {
    if (invalid) { setError('Lengkapi data pelanggan dan layanan.'); return }
    if (creation.beforeSave()) return
    try { if (await submit()) { setOpen(false); setError('') } else setError('PSB belum berhasil dibuat. Periksa data atau coba lagi.') }
    finally { creation.finish() }
  }
  return <><CommandBar primary={{ key: 'create', label: 'Buat PSB', icon: <IconPlus size={16} />, onClick: () => setOpen(true) }} />
    <Blade open={open} title="Buat PSB" onClose={close}
      creation={{ ...creation, busy: saving || creation.busy, steps: [
        { key: 'customer', label: 'Pelanggan' }, { key: 'location', label: 'Lokasi', validate: () => {
          const lat = Number(draft.latitude), lng = Number(draft.longitude)
          return !draft.latitude.trim() || !draft.longitude.trim() || !Number.isFinite(lat) || !Number.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180 ? 'Tentukan lokasi pemasangan yang valid.' : null
        } }, { key: 'plan', label: 'Paket' }, { key: 'network', label: 'Akun jaringan' }, { key: 'installation', label: 'Pemasangan' },
      ], prepare: () => void prepare(), summary: <div className="stack">{error && <p role="alert" className="error">{error}</p>}
        <CreationSummary rows={[
          ['Pelanggan', draft.name], ['Telepon', draft.phone], ['Alamat', draft.address], ['Area', areas.find(area => area.id === draft.areaId)?.name],
          ['Paket', plans.find(plan => plan.id === planId)?.name], ['Layanan', SERVICE_TYPE_LABEL[authType]],
          ['Username', draft.username || 'Otomatis'], ['BRAS', nasList.find(nas => nas.id === nasId)?.name],
          ['Jadwal pemasangan', draft.scheduledAt || 'Belum dijadwalkan'],
        ]} /></div> }}
      footer={<><Button disabled={saving} onClick={close}>Batal</Button><Button variant="primary" disabled={saving || invalid || workflowMode === null} onClick={() => void prepare()}>{saving ? 'Memproses…' : 'Buat PSB'}</Button></>}>
      <div className="stack">{error && <p role="alert" className="error">{error}</p>}
          {/* Pelanggan */}
          <ResourceStep step="customer">

            <div className="row wrap" style={{ gap: '0.6rem' }}>
              <div style={{ flex: 1, minWidth: 140 }}>
                <TextField label="Kode" value={draft.code} onChange={(_, data) => set({ code: data.value })} placeholder="Otomatis: CUST-000001" />
              </div>
              <div style={{ flex: 2, minWidth: 180 }}>
                <TextField label="Nama" required value={draft.name} onChange={(_, data) => set({ name: data.value })} placeholder="Budi Santoso" />
              </div>
              <div style={{ flex: 1, minWidth: 140 }}>
                <TextField label="Telepon" value={draft.phone} onChange={(_, data) => set({ phone: data.value })} placeholder="08123456789" />
              </div>
              <div style={{ flex: 1, minWidth: 140 }}>
                <TextField label="Email" type="email" value={draft.email} onChange={(_, data) => set({ email: data.value })} placeholder="budi@email.com" />
              </div>
            </div>
          </ResourceStep><ResourceStep step="location">
            <TextField label="Alamat" required value={draft.address} onChange={(_, data) => set({ address: data.value })} placeholder="Jl. Merdeka No. 10" />
            <label>
              <span>Lokasi *</span>
              <LocationPicker
                longitude={draft.longitude}
                latitude={draft.latitude}
                onChange={(longitude, latitude) => set({ longitude, latitude })}
                onAddress={(address) => set(draft.address.trim() ? {} : { address })}
              />
            </label>
            {areas.length > 0 && (
              <div className="row wrap" style={{ gap: '0.6rem' }}>
                <div style={{ flex: 1, minWidth: 200 }}>
                  <SelectField
                    label={<>Area {nasByArea.has(draft.areaId) && <span className="muted">· BRAS otomatis</span>}</>}
                    value={draft.areaId}
                    onChange={(_, data) => changeArea(data.value)}
                  >
                    <option value="">— pilih area —</option>
                    {areas.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.name}
                      </option>
                    ))}
                  </SelectField>
                </div>
              </div>
            )}
          </ResourceStep>

          {/* Paket & akun jaringan */}
          <ResourceStep step="plan">

            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <div style={{ flex: 2, minWidth: 180 }}>
                <SelectField label="Paket" required value={planId} onChange={(_, data) => changePlan(data.value)}>
                  {plans.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name} ({p.downMbps}/{p.upMbps} Mbps)
                    </option>
                  ))}
                </SelectField>
              </div>
              <div style={{ flex: 1, minWidth: 140 }}>
                <SelectField label="Tipe layanan" value={authType} onChange={(_, data) => setAuthType(oneOf(data.value, ['PPPOE', 'STATIC', 'HOTSPOT', 'DHCP']))} disabled={availableTypes.length <= 1}>
                  {availableTypes.map((t) => (
                    <option key={t} value={t}>
                      {SERVICE_TYPE_LABEL[t]}
                    </option>
                  ))}
                </SelectField>
              </div>
              <div style={{ flex: 1, minWidth: 150 }}>
                <TextField
                  label="Harga khusus (opsional)"
                  type="number"
                  min={0}
                  value={draft.monthlyFeeOverride}
                  onChange={(_, data) => set({ monthlyFeeOverride: data.value })}
                  placeholder="ikuti harga paket"
                />
              </div>
            </div>

          </ResourceStep><ResourceStep step="network">
            {macBased ? (
              <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
                <div style={{ flex: 2, minWidth: 180 }}>
                  <TextField label="MAC Address" required value={draft.username} onChange={(_, data) => set({ username: data.value })} placeholder="AA:BB:CC:DD:EE:FF" />
                </div>
                <div style={{ flex: 2, minWidth: 160 }}>
                  <TextField label="Reserved IP" required={authType === 'STATIC'} value={draft.framedIp} onChange={(_, data) => set({ framedIp: data.value })} placeholder="100.64.0.10" />
                </div>
              </div>
            ) : (
              <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
                <div style={{ flex: 2, minWidth: 160 }}>
                  <TextField label="Username (opsional)" value={draft.username} onChange={(_, data) => set({ username: data.value })} placeholder="otomatis dari kode pelanggan" />
                </div>
                <div style={{ flex: 2, minWidth: 160 }}>
                  <TextField label="Password" required autoComplete="new-password" type={showSecret ? 'text' : 'password'} value={secret} onChange={(_, data) => setSecret(data.value)} />
                </div>
                <Button type="button" onClick={() => setShowSecret((v) => !v)}>{showSecret ? 'Sembunyikan' : 'Lihat'}</Button>
                <Button type="button" onClick={() => { setSecret(randomSecret()); setShowSecret(true) }}>Buat acak</Button>
              </div>
            )}

            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <div style={{ flex: 1, minWidth: 160 }}>
                <SelectField label="BRAS" value={nasId} onChange={(_, data) => setNasId(data.value)}>
                  <option value="">— tanpa BRAS —</option>
                  {nasList.map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.name}
                    </option>
                  ))}
                </SelectField>
              </div>
              {draft.areaId !== '' && !nasByArea.has(draft.areaId) && (
                <Text as="span" className="muted" size={200} style={{ alignSelf: 'center' }}>
                  Area ini belum dipetakan ke BRAS — pilih manual bila perlu.
                </Text>
              )}
            </div>
            <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
              {macBased
                ? 'DHCP dan Static memakai MAC Address sebagai identitas. Static memerlukan Reserved IP.'
                : 'Password dapat diubah sebelum PSB dibuat dan hanya ditampilkan sekali setelahnya.'}
            </Text>
          </ResourceStep>

          {/* Work order pemasangan */}
          <ResourceStep step="installation">

            {reference && <p>Teknisi dapat ditugaskan setelah PSB dibuat.</p>}
            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <div style={{ flex: 2, minWidth: 200 }}>
                <TextField label="Judul pekerjaan" value={draft.title} onChange={(_, data) => set({ title: data.value })} placeholder={`PSB ${draft.name.trim() || 'pelanggan'}`} />
              </div>
              {workflowMode === 'LEGACY' && <label style={{ flex: 1, minWidth: 180 }}>
                <span>Teknisi (opsional, bisa lebih dari satu)</span>
                <MultiCombobox
                  values={draft.assignees}
                  onChange={(ids) => set({ assignees: ids })}
                  fetchOptions={(term) =>
                    Promise.resolve(
                      technicians.filter((t) => t.name.toLowerCase().includes(term.toLowerCase())),
                    )
                  }
                  toId={(t) => t.id}
                  toLabel={(t) => t.name}
                  debounceMs={0}
                  placeholder="Cari teknisi…"
                  emptyText="Tak ada teknisi"
                />
              </label>}
              <div style={{ flex: 1, minWidth: 180 }}>
                <TextField label="Jadwal" type="datetime-local" value={draft.scheduledAt} onChange={(_, data) => set({ scheduledAt: data.value })} />
              </div>
            </div>
            <TextareaField
              label="Catatan pemasangan"
              rows={2}
              maxLength={2000}
              value={draft.description}
              onChange={(_, data) => set({ description: data.value })}
            />
          </ResourceStep>

      </div>
    </Blade></>
}
