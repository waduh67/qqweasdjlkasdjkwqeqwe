import { describe, expect, it } from 'vitest'
import { spliceCable, spliceFixture, splicePoints } from '@/test/spliceFixture'
import { compatibleSplice, groupSpliceOptions, matchesPurpose, matchingCorePairs, spliceOptions, spliceDetailsError } from './spliceOptions'
import type { FiberConnectionPointView } from '@/api/network'

describe('splice choices', () => {
  it('keeps the remote end available, while excluding local use, damage and unopened cables', () => {
    const data = spliceFixture()
    data.cables[0].cores[1].connectionId = 'used-here'
    data.cables[0].cores[2].core.status = 'DAMAGED'
    data.cables.push({ ...spliceCable('passing'), spliceable: false, role: 'PASSING' })
    const ids = spliceOptions(data).map(option => option.id)
    expect(ids).toContain('in-1')
    expect(ids).not.toContain('in-2'); expect(ids).not.toContain('in-3'); expect(ids).not.toContain('passing-1')
  })
  it('offers only splitter inputs for cable supply and excludes the same cable entirely for continuation', () => {
    const data = spliceFixture(); data.cables.push(spliceCable('out'))
    const options = spliceOptions(data), source = options.find(option => option.id === 'in-7')!
    const supply = options.filter(option => matchesPurpose(option, 'INPUT', 'target') && compatibleSplice(source, option, data))
    expect(supply.map(option => option.point.kind)).toEqual(['SPLITTER_IN'])
    const continuation = options.filter(option => matchesPurpose(option, 'CABLE', 'target') && compatibleSplice(source, option, data))
    expect(new Set(continuation.map(option => option.cableId))).toEqual(new Set(['out']))
  })
  it('does not offer the feeder cable to an output of its own splitter, in either stored orientation', () => {
    for (const reverse of [false, true]) {
      const data = spliceFixture(); data.cables.push(spliceCable('out'))
      const core = { kind: 'CORE', cableId: 'in', cableCode: 'DIST-JB-ODP', nodeId: null, portNumber: null } as FiberConnectionPointView
      const input = { kind: 'SPLITTER_IN', nodeId: 'spl-1', portNumber: null, cableId: null } as FiberConnectionPointView
      data.connections = [{ a: reverse ? input : core, b: reverse ? core : input } as typeof data.connections[number]]
      const options = spliceOptions(data), output = options.find(option => option.point.kind === 'SPLITTER_OUT')!
      const targets = options.filter(option => matchesPurpose(option, 'OUTPUT', 'target') && compatibleSplice(output, option, data))
      expect(new Set(targets.map(option => option.cableId))).toEqual(new Set(['out']))
    }
  })
  it('supports cascading to another splitter, but excludes the same splitter input and all outputs', () => {
    const data = spliceFixture(); data.points.push(...splicePoints('spl-2'))
    const options = spliceOptions(data), source = options.find(option => option.point.kind === 'SPLITTER_OUT')!
    const targets = options.filter(option => matchesPurpose(option, 'CASCADE', 'target') && compatibleSplice(source, option, data))
    expect(targets.map(option => option.point.nodeId)).toEqual(['spl-2'])
  })
  it('keeps ODF back ports for cores and front ports for patching, excluding PON-to-PON and self-patching', () => {
    const data = spliceFixture(); data.points = ['BACK', 'FRONT'].map(portSide => ({ ...splicePoints()[0], kind: 'ODF_PORT', portSide, portNumber: 1, nodeId: 'odf-1' })) as typeof data.points
    data.points.push(...['pon-1', 'pon-2'].map(nodeId => ({ ...splicePoints()[0], kind: 'PON_PORT' as const, nodeId })))
    const options = spliceOptions(data), pon = options.find(option => option.point.kind === 'PON_PORT')!
    expect(options.filter(option => matchesPurpose(option, 'TERMINATION', 'target')).map(option => option.point.portSide)).toEqual(['BACK'])
    expect(options.filter(option => matchesPurpose(option, 'PATCH', 'target') && compatibleSplice(pon, option, data)).map(option => option.point.portSide)).toEqual(['FRONT'])
  })
  it('pairs equal core numbers without shifting around occupied positions', () => {
    const data = spliceFixture(); data.cables = [spliceCable('in', 5), spliceCable('out', 5)]
    data.cables[0].cores[1].connectionId = 'used'; data.cables[1].cores[3].connectionId = 'used'
    const groups = groupSpliceOptions(spliceOptions(data))
    const pairs = matchingCorePairs(groups[0], groups[1], data)
    expect(pairs.map(pair => [pair.a.number, pair.b.number])).toEqual([[1, 1], [3, 3], [5, 5]])
    expect(matchingCorePairs(groups[0], groups[0], data)).toEqual([])
  })
  it('preserves core numbering across tubes and validates optional measured loss', () => {
    const data = spliceFixture(); data.cables = [spliceCable('in', 288)]
    expect(spliceOptions(data).filter(option => option.cableId).map(option => option.number)).toEqual(Array.from({ length: 288 }, (_, i) => i + 1))
    for (const value of ['', '0', '0.08', '999']) expect(spliceDetailsError(value)).toBeNull()
    for (const value of ['-1', 'NaN', 'Infinity', '1000', '0,2']) expect(spliceDetailsError(value)).not.toBeNull()
  })
})
