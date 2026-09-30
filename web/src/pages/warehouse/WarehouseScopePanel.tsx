import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { listLocations } from '@/api/warehouse/masters'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { listSetupUsers, listUserWarehouseScopes, saveUserWarehouseScope } from '@/api/warehouse/setup'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useToast } from '@/system'

type UserChoice = { id: string; label: string; active: boolean }
const locations = (search: string, page: number) => listLocations({ search, page, state: 'ACTIVE' })
const users = async (search: string, page: number) => { const data = await listSetupUsers(search, page); return { ...data, items: data.items.map(row => ({ id: row.id, label: `${row.name} · ${row.email}`, active: row.status === 'ACTIVE' })) } }

export function WarehouseScopePanel() {
  const { user } = useAuth()
  const { can } = useCan()
  const toast = useToast()
  const [target, setTarget] = useState<UserChoice | null>(user ? { id: user.id, label: `${user.name} · ${user.email}`, active: true } : null)
  const [location, setLocation] = useState<WarehouseLocation | null>(null)
  const [operation, setOperation] = useState<{ command: ReturnType<typeof saveUserWarehouseScope>; userName: string; locationName: string; active: boolean; revision: number } | null>(null)
  const loader = useCallback(() => target ? listUserWarehouseScopes(target.id) : Promise.resolve(null), [target])
  const result = useWarehouseQuery(loader)
  return <div className="stack"><h2>Akses pengguna ke gudang</h2>
    <p>Izin operasi, area pengguna, dan akses lokasi harus terpenuhi bersama. Pemberian akses ke lokasi induk mencakup lokasi turunannya.</p>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(18rem, 100%), 1fr))', gap: '1rem' }}>
      {can('iam.user.view') ? <WarehousePicker<UserChoice> label="Pengguna" load={users} value={target} onChange={setTarget} name={choice => choice.label} eligible={choice => choice.active} /> : <div><strong>{target?.label}</strong><p className="muted">Izin lihat pengguna diperlukan untuk memilih akun lain.</p></div>}
      <WarehousePicker label="Lokasi" load={locations} value={location} onChange={setLocation} name={row => `${row.name ?? row.code} · ${row.code}`} />
    </div>
    <WarehouseState {...result}>{grants => {
      if (!target || !location || grants === null) return <p className="muted">Pilih pengguna dan lokasi untuk memeriksa akses yang tersimpan.</p>
      const grant = grants.find(row => row.locationId === location.id)
      const active = grant?.active === true
      return <div className="card stack">
        <strong>{target.label}</strong><span>{location.name ?? location.code} · {location.code}</span>
        <p>{grant ? `Pemberian akses langsung: ${active ? 'aktif' : 'dicabut'} · Revisi ${grant.revision}` : 'Belum ada pemberian akses langsung untuk lokasi ini.'}</p>
        <p className="muted">Akses yang diwarisi dari lokasi induk tetap mengikuti pemberian pada induknya. Izin area diperiksa kembali oleh server.</p>
        {can('inventory.location.manage') ? <Button variant="primary" onClick={() => setOperation({ command: saveUserWarehouseScope(target.id, location.id, grant?.revision ?? 0, !active), userName: target.label, locationName: `${location.name ?? location.code} · ${location.code}`, active: !active, revision: grant?.revision ?? 0 })}>
          {active ? 'Cabut akses langsung' : 'Berikan akses langsung'}
        </Button> : <p className="muted">Akses baca saja. Izin kelola lokasi diperlukan untuk mengubah pemberian akses.</p>}
      </div>
    }}</WarehouseState>
    {can('iam.user.view') && <Link to="/users">Kelola role dan area pengguna</Link>}
    {operation && <WarehouseCommandDialog title={operation.active ? 'Berikan akses gudang' : 'Cabut akses gudang'} command={operation.command} confirmLabel={operation.active ? 'Berikan akses' : 'Cabut akses'}
      summary={<><p><strong>{operation.userName}</strong></p><p>{operation.locationName} · Revisi pemberian akses {operation.revision}</p><p>{operation.active ? 'Pengguna mendapat cakupan lokasi ini dan turunannya sesuai izin serta area yang dimiliki.' : 'Pemberian akses langsung ini dicabut. Akses dari lokasi induk, bila ada, tetap berlaku.'}</p></>}
      onDone={() => { setOperation(null); result.reload(); toast.success('Akses gudang disimpan') }} onClose={() => setOperation(null)} onReload={() => { setOperation(null); result.reload() }} />}
  </div>
}
