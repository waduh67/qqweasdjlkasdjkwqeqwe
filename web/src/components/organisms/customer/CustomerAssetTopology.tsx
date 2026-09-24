import { useCallback } from 'react'
import { api } from '@/api/client'
import { array, integer, record, text, uuid } from '@/api/warehouse/codec'
import type { AssetTopologyDraft } from './customerAssetDraft'
import { parameters } from '@/api/warehouse/transport'
import { TextField } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'

export function CustomerAssetTopology({ value, onChange, disabled, required = false }: { value: AssetTopologyDraft; onChange: (value: AssetTopologyDraft) => void; disabled: boolean; required?: boolean }) {
  const load = useCallback(async (search: string, page: number) => {
    const r = record(await api.get(`/api/odps${parameters({ query: search, page, size: 25 })}`))
    return { items: array(r.content, value => { const o = record(value); return { id: uuid(o.id), code: text(o.code), capacity: integer(o.capacity) } }), page, size: 25, totalElements: integer(r.totalElements) }
  }, [])
  return <><WarehousePicker label="ODP tujuan" load={load} value={value.odp} onChange={odp => onChange({ odp, port: '', rx: '' })} name={odp => `${odp.code} · ${odp.capacity} port`} disabled={disabled} optional={!required} />
    {value.odp && <div className="row wrap"><TextField label="Port ODP" required inputMode="numeric" value={value.port} disabled={disabled} onChange={(_, data) => onChange({ ...value, port: data.value })} />
      <TextField label="Redaman instalasi (dBm)" value={value.rx} disabled={disabled} onChange={(_, data) => onChange({ ...value, rx: data.value })} placeholder="-22.5" /></div>}
  </>
}
