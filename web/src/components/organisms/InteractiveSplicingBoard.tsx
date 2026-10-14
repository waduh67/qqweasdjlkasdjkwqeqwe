import { useMemo, useRef, useState, type CSSProperties } from 'react'
import { ArrowRight, Cable, Check, RefreshCw, Settings2, X } from 'lucide-react'
import { api, ApiError } from '@/api/client'
import type { SpliceWorkbenchView } from '@/api/network'
import { Button } from '@/components/atoms'
import { CommandBar } from '@/components/molecules'
import { useToast } from '@/system'
import {
  compatibleSplice, groupSpliceOptions, matchesPurpose, purposesFor, spliceDetailsError,
  spliceOptions, type SpliceGroup, type SpliceOption, type SplicePurpose,
} from '@/utils/spliceOptions'
import { initialSpliceDetails, SpliceDetailFields } from './SpliceDetailFields'

const labels: Record<SplicePurpose, string> = {
  INPUT: 'Input splitter', OUTPUT: 'Output splitter', CABLE: 'Antar kabel',
  CASCADE: 'Splitter bertingkat', TERMINATION: 'Terminasi ODF', PATCH: 'Patchcord ODF / OLT',
}
const sides: Record<SplicePurpose, [string, string]> = {
  INPUT: ['Core kabel', 'Input splitter'], OUTPUT: ['Output splitter', 'Core kabel'], CABLE: ['Core sumber', 'Core tujuan'],
  CASCADE: ['Output splitter', 'Input splitter'], TERMINATION: ['Core kabel', 'Port belakang ODF'], PATCH: ['Port sumber', 'Port tujuan'],
}

function fiberStyle(hex?: string | null): CSSProperties | undefined {
  if (!hex || !/^#(?:[a-f\d]{3}|[a-f\d]{6})$/i.test(hex)) return undefined
  const rgb = hex.length === 4 ? [...hex.slice(1)].map(channel => channel.repeat(2)).join('') : hex.slice(1)
  const [r, g, b] = [0, 2, 4].map(offset => {
    const channel = parseInt(rgb.slice(offset, offset + 2), 16) / 255
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  })
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
  // Choose the higher WCAG contrast ratio; either black or white exceeds 4.5:1.
  const ink = (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05) ? '#000000' : '#ffffff'
  return { '--fiber-color': hex, '--fiber-ink': ink } as CSSProperties
}

function Points({ groups, side, selected, disabled, onSelect }: {
  groups: SpliceGroup[]; side: 'sumber' | 'tujuan'; selected?: SpliceOption; disabled: boolean; onSelect: (option: SpliceOption) => void
}) {
  return <div className="splice-point-groups">{groups.map(group => <section key={group.id} className="splice-point-group" aria-label={group.label}>
    <div className="splice-group-heading"><h5>{group.label}</h5><span>{group.options.length} tersedia</span></div>
    <div className="splice-point-grid">{group.options.map(option => <button key={option.id} type="button"
      className={`splice-point${option.point.kind === 'CORE' ? ' splice-point-core' : ''}`}
      style={option.point.kind === 'CORE' ? fiberStyle(option.colorHex) : undefined}
      aria-label={`Pilih ${side} ${option.fullLabel}`} aria-pressed={selected?.id === option.id}
      title={option.fullLabel} disabled={disabled} onClick={() => onSelect(option)}>
      <span>{option.point.kind === 'CORE' ? option.number : option.label}</span>
      {selected?.id === option.id && <Check size={12} className="splice-point-check" aria-hidden="true" />}
    </button>)}</div>
  </section>)}</div>
}

export function InteractiveSplicingBoard({ data, disabled, onSaved, onBusyChange, onRefresh, onBulk }: {
  data: SpliceWorkbenchView; disabled: boolean; onSaved: () => void; onBusyChange: (busy: boolean) => void;
  onRefresh: () => void; onBulk: () => void
}) {
  const toast = useToast(), saving = useRef(false)
  const purposes = purposesFor(data)
  const [purpose, setPurpose] = useState<SplicePurpose>(purposes[0])
  const [sourceId, setSourceId] = useState(''), [targetId, setTargetId] = useState('')
  const [details, setDetails] = useState(initialSpliceDetails), [showDetails, setShowDetails] = useState(false)
  const [error, setError] = useState(''), [isSaving, setSaving] = useState(false)
  const options = useMemo(() => spliceOptions(data), [data])
  const sources = options.filter(option => matchesPurpose(option, purpose, 'source'))
  const source = sources.find(option => option.id === sourceId)
  const targets = source ? options.filter(option => matchesPurpose(option, purpose, 'target') && compatibleSplice(source, option, data)) : []
  const target = targets.find(option => option.id === targetId)
  const full = data.spliceCapacity != null && data.spliceCount >= data.spliceCapacity
  const clear = () => { setSourceId(''); setTargetId(''); setError('') }
  async function connect() {
    if (disabled || saving.current || full || !source || !target) return
    const invalid = spliceDetailsError(details.loss)
    if (invalid) { setError(invalid); setShowDetails(true); return }
    saving.current = true; setSaving(true); onBusyChange(true); setError('')
    try {
      await api.post('/api/fiber-connections', {
        closureKind: data.closureKind, closureId: data.closureId, a: source.point, b: target.point,
        method: details.method, lossDb: details.loss.trim() ? Number(details.loss) : null,
        note: details.note.trim() || null, workOrderId: details.workOrderId || null,
      })
      clear(); setDetails(current => ({ ...current, loss: '', note: '' }))
      toast.success('Sambungan ditambahkan'); onSaved()
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Sambungan gagal disimpan. Coba lagi.')
    } finally { saving.current = false; setSaving(false); onBusyChange(false) }
  }
  return <div className="splice-interactive" aria-label="Meja sambung" role="region">
    <CommandBar actions={[
      { key: 'clear', label: 'Batalkan pilihan', icon: <X size={16} />, disabled: disabled || !sourceId && !targetId, onClick: clear, pinned: true },
      { key: 'details', label: showDetails ? 'Tutup detail' : 'Detail sambungan', icon: <Settings2 size={16} />, disabled, onClick: () => setShowDetails(value => !value), pinned: true },
      { key: 'bulk', label: 'Sambung core 1:1', icon: <Cable size={16} />, disabled: disabled || full, onClick: onBulk },
      { key: 'refresh', label: 'Muat ulang', icon: <RefreshCw size={16} />, disabled, onClick: onRefresh },
    ]} />
    {purposes.length > 1 && <div className="splice-purpose-choices" role="group" aria-label="Jenis sambungan">{purposes.map(key =>
      <button key={key} type="button" disabled={disabled} aria-pressed={purpose === key} onClick={() => { setPurpose(key); clear() }}>{labels[key]}</button>,
    )}</div>}
    {full && <p role="status" className="splice-field-note">Kapasitas sambungan penuh.</p>}
    <div className="splice-interactive-layout">
      <section className="splice-side splice-source" aria-label="Titik sumber">
        <h4><span>1</span>{sides[purpose][0]}</h4>
        <Points groups={groupSpliceOptions(sources)} side="sumber" selected={source} disabled={disabled || full}
          onSelect={option => { setSourceId(sourceId === option.id ? '' : option.id); setTargetId(''); setError('') }} />
        {!sources.length && <p className="splice-board-empty">Tidak ada titik yang tersedia.</p>}
      </section>
      <section className="splice-side splice-target" aria-label="Titik tujuan">
        <h4><span>2</span>{sides[purpose][1]}</h4>
        <Points groups={groupSpliceOptions(targets)} side="tujuan" selected={target} disabled={disabled || full}
          onSelect={option => { setTargetId(targetId === option.id ? '' : option.id); setError('') }} />
        {!source ? <p className="splice-board-empty">Pilih titik sumber.</p> : !targets.length && <p className="splice-board-empty" role="status">{purpose === 'INPUT'
          ? 'Tidak ada input splitter yang tersedia.' : purpose === 'CABLE' ? 'Tidak ada core tersedia pada kabel lain.' : 'Tidak ada tujuan yang tersedia.'}</p>}
      </section>
      <div className={`splice-pair-preview${source && target ? ' splice-pair-ready' : ''}`}>
        <div className="splice-selected-pair" aria-label="Pasangan sambungan" role="status" aria-live="polite">
          <span>{source?.fullLabel ?? 'Belum dipilih'}</span><ArrowRight size={24} aria-hidden="true" /><span>{target?.fullLabel ?? 'Belum dipilih'}</span>
        </div>
        <Button variant="primary" disabled={disabled || full || !source || !target} onClick={() => void connect()}>{isSaving ? 'Menyambungkan…' : 'Sambungkan'}</Button>
        {source?.connectedElsewhere && <p className="splice-field-note">Core ini memiliki sambungan di lokasi lain.</p>}
      </div>
    </div>
    {error && <p role="alert" className="error splice-board-error">{error}</p>}
    {showDetails && <fieldset className="splice-optional-details" disabled={disabled}>
      <legend>Detail sambungan</legend><SpliceDetailFields value={details} onChange={value => { setDetails(value); setError('') }} />
    </fieldset>}
  </div>
}
