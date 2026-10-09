import { TextField } from '@/components/atoms'
import { FormSection } from '@/components/molecules'

export interface TenantOnboardingDraft {
  readonly name: string
  readonly slug: string
  readonly adminName: string
  readonly adminEmail: string
  readonly adminPassword: string
  readonly monthlyFee: string
}

export function TenantOnboardingFields({ draft, defaultFee, onChange }: {
  readonly draft: TenantOnboardingDraft
  readonly defaultFee: number | null
  readonly onChange: (draft: TenantOnboardingDraft) => void
}) {
  return <>
    <FormSection title="Identitas organisasi" description="Nama ditampilkan di aplikasi. Slug dipakai admin saat masuk ke tenant.">
      <div className="form-grid">
        <TextField required label="Nama" autoComplete="organization" value={draft.name} onChange={(_, data) => onChange({ ...draft, name: data.value })} placeholder="PT Fiber Nusantara" />
        <TextField required label="Slug" hint="Huruf kecil, angka, dan tanda hubung." value={draft.slug} onChange={(_, data) => onChange({ ...draft, slug: data.value })} placeholder="pt-fiber" />
      </div>
    </FormSection>
    <FormSection title="Admin pertama" description="Akun ini akan mengelola pengguna dan operasional tenant.">
      <TextField required label="Nama admin" autoComplete="name" value={draft.adminName} onChange={(_, data) => onChange({ ...draft, adminName: data.value })} />
      <TextField required label="Email admin" type="email" autoComplete="email" value={draft.adminEmail} onChange={(_, data) => onChange({ ...draft, adminEmail: data.value })} />
      <TextField required label="Password admin" type="password" autoComplete="new-password" value={draft.adminPassword} onChange={(_, data) => onChange({ ...draft, adminPassword: data.value })} />
    </FormSection>
    <FormSection title="Langganan" description="Biaya khusus bersifat opsional. Kosongkan untuk mengikuti harga platform.">
      <TextField label="Harga bulanan khusus (Rp)" type="number" min={0} step="any" value={draft.monthlyFee} onChange={(_, data) => onChange({ ...draft, monthlyFee: data.value })} placeholder={defaultFee != null ? `Default Rp ${defaultFee.toLocaleString('id-ID')}` : 'Gunakan harga default'} />
    </FormSection>
  </>
}
