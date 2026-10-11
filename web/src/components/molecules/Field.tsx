import { useContext, type ReactNode } from 'react'
import { ResourceStepsContext } from '@/components/organisms/ResourceStep'

/**
 * Seksi form berjudul di dalam [Blade] — memisah kelompok field yang panjang
 * (mis. identitas, SNMP, billing) dengan garis pemisah tipis, seperti section
 * pada blade Azure. Judul opsional (`title`); `description` untuk keterangan singkat.
 */
export function FormSection({
  title,
  description,
  children,
  step,
}: {
  title?: ReactNode
  description?: ReactNode
  children: ReactNode
  step?: string
}) {
  const context = useContext(ResourceStepsContext)
  const hidden = !!step && !!context && context.active !== step
  return (
    <section className="form-section" data-resource-step={step} hidden={hidden} inert={hidden ? true : undefined}>
      {title && !(step && context) && <h3 className="form-section-title">{title}</h3>}
      {description && <p className="form-section-desc">{description}</p>}
      <div className="stack form-section-fields">
        {children}
      </div>
    </section>
  )
}

/**
 * Field berlabel — pembungkus tipis `<label><span>…</span>{control}</label>` agar
 * label, teks bantuan, dan pesan galat konsisten di seluruh form. `hint` tampil di
 * bawah kontrol; `error` menggantikan warna & pesan bila ada. `required` memberi
 * tanda bintang.
 */
export function Field({
  label,
  hint,
  error,
  required,
  htmlFor,
  children,
  style,
}: {
  label: ReactNode
  hint?: ReactNode
  error?: ReactNode
  required?: boolean
  htmlFor?: string
  children: ReactNode
  style?: React.CSSProperties
}) {
  return (
    <label className={`field${error ? ' field-error' : ''}`} htmlFor={htmlFor} style={style}>
      <span className="field-label">
        {label}
        {required && <span className="field-req" aria-hidden> *</span>}
      </span>
      {children}
      {error ? (
        <span className="field-msg field-msg-error" role="alert">
          {error}
        </span>
      ) : (
        hint && <span className="field-msg">{hint}</span>
      )}
    </label>
  )
}
