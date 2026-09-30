import { describe, expect, it } from 'vitest'
import type { MaterialPlan, MaterialSummary } from '@/api/warehouse/materialModels'
import { buildMaterialPlan, emptyMaterialRow, rowsFromMaterialPlan } from './materialDraft'

const sku = { id: '11111111-1111-4111-8111-111111111111', revision: 4, code: 'CABLE', name: 'Kabel drop', tracking: 'LOT' as const, baseUnit: 'MM' as const }
const line: MaterialPlan['lines'][number] = { id: '22222222-2222-4222-8222-222222222222', lineNumber: 1, sku, quantityBase: '82501', continuousCut: true, substitution: null, originalSku: null }
const summary = { revisions: { planRevision: 7, workOrderRevision: 12 } } as MaterialSummary

describe('material plan drafts', () => {
  it('copies reviewed template amounts exactly and sends explicit lines and captured revisions', () => {
    const rows = rowsFromMaterialPlan([line], false)
    expect(rows[0].quantity).toBe('82,501')
    expect(rows[0].previous).toBeNull()
    expect(buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', rows, false)).toEqual({ expectedRevision: 7, workOrderRevision: 12, materialMode: 'MATERIAL_REQUIRED', reason: null,
      lines: [{ skuId: sku.id, quantityBase: '82501', baseUnit: 'MM', continuousCut: true }] })
  })
  it('requires an explicit reason for NONE and removes dormant material rows', () => {
    expect(() => buildMaterialPlan(summary, 'NONE', ' ', [emptyMaterialRow()], false)).toThrow('alasan')
    expect(buildMaterialPlan(summary, 'NONE', ' Pemeriksaan visual ', rowsFromMaterialPlan([line]), false)).toMatchObject({ materialMode: 'NONE', reason: 'Pemeriksaan visual', lines: [] })
  })
  it('rejects duplicate SKUs, zero, rounded lengths and fractional units', () => {
    const rows = rowsFromMaterialPlan([line])
    expect(() => buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', [...rows, ...rows], true)).toThrow('lebih dari sekali')
    for (const quantity of ['0', '0,0001', '1.000,000']) expect(() => buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', [{ ...rows[0], quantity }], true)).toThrow()
    expect(() => buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', [{ ...emptyMaterialRow(), sku: { ...sku, tracking: 'SERIAL', baseUnit: 'EA' }, quantity: '1,5' }], true)).toThrow('bilangan bulat')
  })
  it('binds a replacement to the current plan line and requires permission, compatibility and reason', () => {
    const row = { ...rowsFromMaterialPlan([line])[0], sku: { ...sku, id: '33333333-3333-4333-8333-333333333333', name: 'Kabel pengganti' } }
    expect(() => buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', [row], false)).toThrow('izin substitusi')
    expect(() => buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', [row], true)).toThrow('alasan penggantian')
    expect(() => buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', [{ ...row, sku: { ...row.sku, tracking: 'BULK' } }], true)).toThrow('pelacakan')
    expect(buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', [{ ...row, substitutionReason: ' Stok pengganti disetujui ' }], true).lines?.[0].substitution).toEqual({ originalPlanLineId: line.id, originalSkuId: sku.id, reason: 'Stok pengganti disetujui' })
  })
  it('does not reuse an older substitution source when revising an already replaced SKU', () => {
    const old = { ...line, substitution: { originalPlanLineId: 'older-plan-line', originalSkuId: 'older-sku', reason: 'Earlier replacement' }, originalSku: { ...sku, id: 'older-sku' } }
    const rows = rowsFromMaterialPlan([old])
    expect(buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', rows, true).lines?.[0].substitution).toBeUndefined()
    rows[0].sku = { ...sku, id: 'new-sku' }; rows[0].substitutionReason = 'New replacement'
    expect(buildMaterialPlan(summary, 'MATERIAL_REQUIRED', '', rows, true).lines?.[0].substitution?.originalPlanLineId).toBe(line.id)
  })
})
