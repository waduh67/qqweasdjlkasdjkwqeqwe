import type { ConnectionPointRequest, FiberConnectionPointView, SpliceWorkbenchView } from '../api/network'
import { impossibleSpliceWarning, type SpliceEnd } from './spliceGuard'

export type SplicePurpose = 'INPUT' | 'OUTPUT' | 'CABLE' | 'CASCADE' | 'TERMINATION' | 'PATCH'
export const splicePurposes: Record<SplicePurpose, string> = {
  INPUT: 'Kabel ke input splitter', OUTPUT: 'Output splitter ke kabel', CABLE: 'Antar kabel',
  CASCADE: 'Splitter bertingkat', TERMINATION: 'Kabel ke port ODF', PATCH: 'Patchcord ODF / OLT',
}
export interface SpliceOption {
  id: string; groupId: string; groupLabel: string; label: string; fullLabel: string
  point: ConnectionPointRequest; cableId: string | null; cableCode: string | null
  number: number | null; connectedElsewhere: boolean
}
export interface SpliceGroup { id: string; label: string; options: SpliceOption[] }

/** Availability is local to this closure. A connection at the far end is expected. */
export function spliceOptions(data: SpliceWorkbenchView): SpliceOption[] {
  return [
    ...data.cables.flatMap(cable => !cable.spliceable ? [] : cable.cores
      .filter(entry => !entry.connectionId && entry.core.status !== 'DAMAGED')
      .sort((a, b) => a.core.coreNumber - b.core.coreNumber)
      .map(entry => {
        const label = `Core ${entry.core.coreNumber} · ${entry.core.color}`
        return { id: entry.core.id, groupId: `cable:${cable.cableId}`, groupLabel: cable.code, label,
          fullLabel: `${cable.code} · ${label}`, point: { kind: 'CORE' as const, coreId: entry.core.id },
          cableId: cable.cableId, cableCode: cable.code, number: entry.core.coreNumber, connectedElsewhere: entry.connectedElsewhere }
      })),
    ...data.points.filter(point => !point.connectionId).map(point => {
      const label = point.kind === 'SPLITTER_IN' ? 'Input' : point.kind === 'SPLITTER_OUT' ? `Output ${point.portNumber}`
        : point.kind === 'ODF_PORT' ? `Port ${point.portNumber} · ${point.portSide === 'BACK' ? 'Belakang' : 'Depan'}` : point.label
      return { id: `${point.kind}:${point.nodeId}:${point.portNumber ?? ''}:${point.portSide ?? ''}`,
        groupId: `${point.kind}:${point.group}`, groupLabel: point.group, label, fullLabel: `${point.group} · ${label}`,
        point: { kind: point.kind, nodeId: point.nodeId, portNumber: point.portNumber, portSide: point.portSide },
        cableId: null, cableCode: null, number: point.portNumber, connectedElsewhere: false }
    }),
  ]
}

export function purposesFor(data: SpliceWorkbenchView): SplicePurpose[] {
  if (data.closureKind === 'ODF') return ['TERMINATION', 'PATCH', 'CABLE']
  if (data.closureKind === 'JOINT_BOX') return ['CABLE']
  return ['INPUT', 'OUTPUT', 'CABLE', 'CASCADE']
}

export function matchesPurpose(option: SpliceOption, purpose: SplicePurpose, side: 'source' | 'target'): boolean {
  const { kind, portSide } = option.point
  switch (purpose) {
    case 'INPUT': return kind === (side === 'source' ? 'CORE' : 'SPLITTER_IN')
    case 'OUTPUT': return kind === (side === 'source' ? 'SPLITTER_OUT' : 'CORE')
    case 'CABLE': return kind === 'CORE'
    case 'CASCADE': return kind === (side === 'source' ? 'SPLITTER_OUT' : 'SPLITTER_IN')
    case 'TERMINATION': return side === 'source' ? kind === 'CORE' : kind === 'ODF_PORT' && portSide === 'BACK'
    case 'PATCH': return kind === 'PON_PORT' || kind === 'ODF_PORT' && portSide === 'FRONT'
  }
}

function end(option: SpliceOption): SpliceEnd {
  return { kind: option.point.kind, nodeId: option.point.nodeId ?? null, portNumber: option.point.portNumber ?? null,
    cableId: option.cableId, cableCode: option.cableCode }
}
function wiredEnd(point: FiberConnectionPointView): SpliceEnd {
  return { kind: point.kind, nodeId: point.nodeId, portNumber: point.portNumber, cableId: point.cableId, cableCode: point.cableCode }
}

export function compatibleSplice(a: SpliceOption, b: SpliceOption, data: SpliceWorkbenchView): boolean {
  if (a.id === b.id || a.cableId && a.cableId === b.cableId) return false
  if (a.point.kind === 'PON_PORT' && b.point.kind === 'PON_PORT') return false
  return !impossibleSpliceWarning(end(a), end(b), data.connections.map(row => ({ a: wiredEnd(row.a), b: wiredEnd(row.b) })))
}

export function groupSpliceOptions(options: SpliceOption[]): SpliceGroup[] {
  const groups = new Map<string, SpliceGroup>()
  for (const option of options) {
    if (!groups.has(option.groupId)) groups.set(option.groupId, { id: option.groupId, label: option.groupLabel, options: [] })
    groups.get(option.groupId)!.options.push(option)
  }
  return [...groups.values()]
}

/** 1:1 means equal core numbers, never zipping two lists after removing occupied cores. */
export function matchingCorePairs(source: SpliceGroup | undefined, target: SpliceGroup | undefined, data: SpliceWorkbenchView) {
  if (!source || !target || source.id === target.id) return []
  return source.options.flatMap(a => {
    const b = target.options.find(option => option.number === a.number)
    return b && compatibleSplice(a, b, data) ? [{ a, b }] : []
  })
}

export function spliceDetailsError(loss: string): string | null {
  const value = Number(loss)
  return loss.trim() && (!Number.isFinite(value) || value < 0 || value > 999) ? 'Redaman harus antara 0 dan 999 dB.' : null
}

export function connectionLabel(point: FiberConnectionPointView): string {
  return point.coreNumber != null ? `${point.cableCode ?? 'Kabel'} · Core ${point.coreNumber}` : point.label.replace(/kaki /gi, 'Output ')
}
