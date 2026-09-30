import type { PolicyChoice, PolicyDetails, PolicyInput, PolicyOperation } from '@/api/warehouse/policy'

export type PolicyLocation = PolicyDetails['references']['locations'][number]
export type PolicyMember = PolicyChoice & { kind: 'USER' | 'ROLE' }
export interface PolicyRuleDraft { operation: PolicyOperation; tiers: { minimum: string; members: PolicyMember[] }[] }
export function policyRulesDraft(settings: PolicyDetails): PolicyRuleDraft[] {
  return settings.current?.rules.map(rule => ({ operation: rule.operation, tiers: rule.tiers.map(tier => ({ minimum: tier.minimumMinor, members: [
    ...tier.userIds.map(id => ({ id, kind: 'USER' as const, name: settings.references.users.find(row => row.id === id)?.name ?? `Pengguna tidak tersedia (${id})` })),
    ...tier.roleIds.map(id => ({ id, kind: 'ROLE' as const, name: settings.references.roles.find(row => row.id === id)?.name ?? `Role tidak tersedia (${id})` })),
  ] })) })) ?? [{ operation: 'ADJUSTMENT', tiers: [{ minimum: '1', members: [] }] }]
}
export function buildPolicy(settings: PolicyDetails, currency: string, expiry: string, locations: PolicyLocation[], rules: PolicyRuleDraft[]): PolicyInput {
  if (!/^[A-Z]{3}$/.test(currency) || !/^[1-9][0-9]*$/.test(expiry) || Number(expiry) > 720) throw new Error('Isi kode mata uang tiga huruf dan masa berlaku 1–720 jam.')
  if (!locations.length || locations.length > 100 || new Set(locations.map(row => row.id)).size !== locations.length) throw new Error('Pilih sedikitnya satu lokasi kebijakan, maksimal 100 lokasi.')
  if (!rules.length || rules.length > 9 || new Set(rules.map(row => row.operation)).size !== rules.length) throw new Error('Setiap jenis transaksi hanya boleh memiliki satu aturan.')
  return { expectedRevision: settings.current?.revision ?? 0, currency, expiryHours: Number(expiry), warehouseIds: locations.map(row => row.id), rules: rules.map(rule => {
    if (!rule.tiers.length || rule.tiers.length > 10) throw new Error('Setiap aturan memerlukan 1–10 tahap persetujuan.')
    let previous = 0n
    return { operation: rule.operation, tiers: rule.tiers.map(tier => {
      if (!/^[1-9][0-9]{0,37}$/.test(tier.minimum) || BigInt(tier.minimum) <= previous) throw new Error('Batas nilai tiap tahap harus bilangan bulat positif yang meningkat, maksimal 38 digit.')
      if (!tier.members.length || tier.members.length > 100 || new Set(tier.members.map(member => `${member.kind}:${member.id}`)).size !== tier.members.length) throw new Error('Pilih 1–100 pengguna atau role pemeriksa berbeda per tahap.')
      previous = BigInt(tier.minimum)
      return { minimumMinor: tier.minimum, userIds: tier.members.filter(row => row.kind === 'USER').map(row => row.id), roleIds: tier.members.filter(row => row.kind === 'ROLE').map(row => row.id) }
    }) }
  }) }
}
export function policyFingerprint(input: Omit<PolicyInput, 'expectedRevision'>) {
  return JSON.stringify({ currency: input.currency, expiryHours: input.expiryHours, warehouseIds: [...input.warehouseIds].sort(), rules: input.rules.map(rule => ({ ...rule, tiers: rule.tiers.map(tier => ({ ...tier, userIds: [...tier.userIds].sort(), roleIds: [...tier.roleIds].sort() })) })).sort((a, b) => a.operation.localeCompare(b.operation)) })
}
