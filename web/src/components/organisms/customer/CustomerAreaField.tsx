import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { Area } from '@/api/types'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, SelectField } from '@/components/atoms'

/** Preserve an existing assignment while offering only areas accessible to this operator. */
export function CustomerAreaField({ value, onChange }: { value: string | null; onChange: (areaId: string | null) => void }) {
  const { user } = useAuth(), { can } = useCan()
  const allowed = can('iam.area.view')
  const [areas, setAreas] = useState<Area[]>([]), [loading, setLoading] = useState(allowed)
  const [failed, setFailed] = useState(false), [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (!allowed) return
    let active = true
    setLoading(true); setFailed(false)
    void api.get<Area[]>('/api/areas').then(rows => { if (active) setAreas(rows) }, () => { if (active) setFailed(true) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [allowed, attempt])
  const ids = new Set(user?.areaIds ?? [])
  for (let pass = 0; pass < areas.length; pass++) {
    const size = ids.size
    for (const area of areas) if (area.parentId && ids.has(area.parentId)) ids.add(area.id)
    if (size === ids.size) break
  }
  // Customer administration preserves its existing empty = unrestricted area
  // contract. Warehouse commands independently require explicit current scopes.
  const unrestricted = user != null && (user.platformAdmin || user.areaIds.length === 0)
  const choices = unrestricted ? areas : areas.filter(area => ids.has(area.id))
  return <div className="stack">
    <SelectField label="Area pelanggan" value={value ?? ''} disabled={!allowed || loading || failed}
      hint="Pilih area layanan agar petugas yang memiliki cakupan area dapat menangani pelanggan ini."
      onChange={(_, data) => onChange(data.value || null)}>
      <option value="">Belum ditempatkan ke area</option>
      {value && !choices.some(area => area.id === value) && <option value={value} disabled>Area tersimpan (nama tidak dapat diakses)</option>}
      {choices.map(area => <option key={area.id} value={area.id}>{area.name} · {area.code}</option>)}
    </SelectField>
    {!allowed && <p className="muted">Izin lihat area diperlukan untuk mengubah area pelanggan.</p>}
    {failed && <div role="alert"><p>Area belum berhasil dimuat. Penempatan sebelumnya tetap tersimpan.</p><Button onClick={() => setAttempt(value => value + 1)}>Muat ulang area</Button></div>}
  </div>
}
