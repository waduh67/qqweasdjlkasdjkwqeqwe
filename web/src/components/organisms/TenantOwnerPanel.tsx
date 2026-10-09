import { useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/client'
import { bindTenantOwner, getPlatformTenant, resetTenantOwnerPassword, type PlatformTenant, type TenantOwner } from '@/api/tenant'
import { Button, StatusBadge } from '@/components/atoms'
import { FormSection } from '@/components/molecules'
import { Blade } from './Blade'
import { TenantOwnerSelector } from './TenantOwnerSelector'
import { TenantOwnerPasswordForm } from './TenantOwnerPasswordForm'

export function TenantOwnerPanel({ tenant, onClose, onSaved }: {
  readonly tenant: PlatformTenant
  readonly onClose: () => void
  readonly onSaved: (notice: string) => void
}) {
  const [detail, setDetail] = useState<PlatformTenant | null>(null)
  const [selected, setSelected] = useState<TenantOwner | null>(null)
  const [loading, setLoading] = useState(true)
  const [operation, setOperation] = useState<'owner' | 'password' | null>(null)
  const pending = useRef(false)
  const active = useRef(true)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])
  useEffect(() => {
    let active = true
    setLoading(true)
    setError(null)
    void getPlatformTenant(tenant.id).then(result => {
      if (active) { setDetail(result); setSelected(result.owner ?? null) }
    }).catch(err => { if (active) setError(err instanceof ApiError ? err.message : 'Gagal memuat owner tenant') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [tenant.id, retry])
  async function bind() {
    if (!selected || pending.current) return
    pending.current = true
    setOperation('owner')
    setError(null)
    try {
      await bindTenantOwner(tenant.id, selected.id)
      if (active.current) onSaved('Owner ' + tenant.name + ' diubah menjadi ' + selected.name + '. Sesi lama telah berakhir.')
    } catch (err) { if (active.current) setError(err instanceof ApiError ? err.message : 'Gagal menyimpan owner') }
    finally { pending.current = false; if (active.current) setOperation(null) }
  }
  async function resetPassword(password: string): Promise<boolean> {
    if (!detail?.owner || pending.current) return false
    pending.current = true
    setOperation('password')
    try {
      await resetTenantOwnerPassword(tenant.id, { expectedOwnerUserId: detail.owner.id, newPassword: password })
      if (active.current) onSaved('Password owner ' + tenant.name + ' sudah diganti. Sesi lama telah berakhir.')
      return active.current
    } finally { pending.current = false; if (active.current) setOperation(null) }
  }
  const busy = operation !== null
  return <Blade open title={'Owner · ' + tenant.name} closeDisabled={busy} onClose={() => { if (!pending.current) onClose() }}
    footer={<Button disabled={busy} onClick={onClose}>Batal</Button>}>
    <div className="stack">
      {loading && <p role="status">Memuat owner…</p>}
      {error && <div role="alert"><p>{error}</p>{!detail && <Button onClick={() => setRetry(value => value + 1)}>Coba lagi</Button>}</div>}
      {detail && !loading && <>
        <FormSection title="Owner saat ini" description="Owner memiliki seluruh akses operasional tenant.">
          {detail.owner ? <div className="stack"><strong>{detail.owner.name}</strong><span>{detail.owner.email}</span><StatusBadge status={detail.owner.status} /></div> :
            <p>Belum ditentukan. Pilih pengguna aktif tenant ini sebagai owner.</p>}
        </FormSection>
        <FormSection title="Pilih owner" description="Akun yang dipilih mendapat akses penuh tenant. Sesi owner lama dan baru akan berakhir.">
          <TenantOwnerSelector tenantId={tenant.id} selected={selected} onSelect={setSelected} busy={busy} />
          <Button variant="primary" disabled={busy || !selected || selected.id === detail.owner?.id} onClick={() => void bind()}>
            {operation === 'owner' ? 'Menyimpan…' : 'Simpan owner'}
          </Button>
        </FormSection>
        {detail.owner && <FormSection title="Ganti password" description="Gunakan saat owner membutuhkan bantuan akses akun.">
          <TenantOwnerPasswordForm owner={detail.owner} busy={busy} resetting={operation === 'password'} onReset={resetPassword} />
        </FormSection>}
      </>}
    </div>
  </Blade>
}
