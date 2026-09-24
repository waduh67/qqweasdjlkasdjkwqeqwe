import { approvalOperationLabels } from './approvalPresentation'
import type { PolicyLocation, PolicyRuleDraft } from './policyDraft'
import { locationLabel } from './receiptChoices'

export function WarehousePolicyPreview({ locations, rules, currency, expiry }: { locations: PolicyLocation[]; rules: PolicyRuleDraft[]; currency: string; expiry: string }) {
  return <div className="stack"><p>Mata uang {currency} · Berlaku {expiry} jam</p><p>Lokasi: {locations.map(locationLabel).join('; ')}</p>
    <ul>{rules.map(rule => <li key={rule.operation}><strong>{approvalOperationLabels[rule.operation]}</strong><ol>{rule.tiers.map((tier, index) => <li key={index}>Mulai {new Intl.NumberFormat('id-ID').format(BigInt(tier.minimum))} unit terkecil {currency}: {tier.members.map(row => `${row.kind === 'ROLE' ? 'Role' : 'Pengguna'} ${row.name}`).join(', ')}</li>)}</ol></li>)}</ul>
  </div>
}
