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
  randomSecret, nasId, setNasId, nasList, reference, workflowMode, technicians, submit, saving, invalid
}: ReturnType<typeof useExpressPsb>) {
  return <div className="card stack" style={{ gap: '1rem' }}>
          {/* Pelanggan */}
          <section className="stack" style={{ gap: '0.5rem' }}>
            <Text as="h3" weight="semibold" size={300} style={{ margin: 0 }}>Pelanggan</Text>
            <div className="row wrap" style={{ gap: '0.6rem' }}>
              <div style={{ flex: 1, minWidth: 140 }}>
                <TextField label="Kode" value={draft.code} onChange={(_, data) => set({ code: data.value })} placeholder="Otomatis: CUST-000001" />
              </div>
              <div style={{ flex: 2, minWidth: 180 }}>
                <TextField label="Nama *" value={draft.name} onChange={(_, data) => set({ name: data.value })} placeholder="Budi Santoso" />
              </div>
              <div style={{ flex: 1, minWidth: 140 }}>
                <TextField label="Telepon" value={draft.phone} onChange={(_, data) => set({ phone: data.value })} placeholder="08123456789" />
              </div>
              <div style={{ flex: 1, minWidth: 140 }}>
                <TextField label="Email" value={draft.email} onChange={(_, data) => set({ email: data.value })} placeholder="budi@email.com" />
              </div>
            </div>
            <TextField label="Alamat *" value={draft.address} onChange={(_, data) => set({ address: data.value })} placeholder="Jl. Merdeka No. 10" />
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
          </section>

          {/* Paket & akun jaringan */}
          <section className="stack" style={{ gap: '0.5rem' }}>
            <Text as="h3" weight="semibold" size={300} style={{ margin: 0 }}>Paket &amp; akun jaringan</Text>
            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <div style={{ flex: 2, minWidth: 180 }}>
                <SelectField label="Paket *" value={planId} onChange={(_, data) => changePlan(data.value)}>
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

            {macBased ? (
              <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
                <div style={{ flex: 2, minWidth: 180 }}>
                  <TextField label="MAC Address *" value={draft.username} onChange={(_, data) => set({ username: data.value })} placeholder="AA:BB:CC:DD:EE:FF" />
                </div>
                <div style={{ flex: 2, minWidth: 160 }}>
                  <TextField label={`Reserved IP${authType === 'STATIC' ? ' *' : ' (opsional)'}`} value={draft.framedIp} onChange={(_, data) => set({ framedIp: data.value })} placeholder="100.64.0.10" />
                </div>
              </div>
            ) : (
              <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
                <div style={{ flex: 2, minWidth: 160 }}>
                  <TextField label="Username (opsional)" value={draft.username} onChange={(_, data) => set({ username: data.value })} placeholder="otomatis dari kode pelanggan" />
                </div>
                <div style={{ flex: 2, minWidth: 160 }}>
                  <TextField label="Password *" type={showSecret ? 'text' : 'password'} value={secret} onChange={(_, data) => setSecret(data.value)} />
                </div>
                <Button type="button" onClick={() => setShowSecret((v) => !v)}>{showSecret ? 'Sembunyikan' : 'Lihat'}</Button>
                <Button type="button" onClick={() => { setSecret(randomSecret()); setShowSecret(true) }}>Generate</Button>
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
          </section>

          {/* Work order pemasangan */}
          <section className="stack" style={{ gap: '0.5rem' }}>
            <Text as="h3" weight="semibold" size={300} style={{ margin: 0 }}>Pemasangan (Work Order PSB)</Text>
            {reference && <p>PSB masuk ke antrean Belum ditugaskan. Setelah dibuat, buka pekerjaan untuk memilih jenis pemasangan dan satu teknisi NE atau FO.</p>}
            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <div style={{ flex: 2, minWidth: 200 }}>
                <TextField label="Judul WO (opsional)" value={draft.title} onChange={(_, data) => set({ title: data.value })} placeholder={`PSB ${draft.name.trim() || 'pelanggan'}`} />
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
                <TextField label="Jadwal (opsional)" type="datetime-local" value={draft.scheduledAt} onChange={(_, data) => set({ scheduledAt: data.value })} />
              </div>
            </div>
            <TextareaField
              label="Catatan pemasangan (opsional)"
              rows={2}
              maxLength={2000}
              value={draft.description}
              onChange={(_, data) => set({ description: data.value })}
            />
          </section>

          <div className="row" style={{ gap: '0.5rem' }}>
            <Button variant="primary" onClick={() => void submit()} disabled={saving || invalid || workflowMode === null}>
              <IconPlus size={15} /> {saving ? 'Memproses…' : 'Buat PSB'}
            </Button>
          </div>
  </div>
}
