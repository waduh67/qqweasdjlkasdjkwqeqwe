import {
  Field,
  Input,
  Select,
  Textarea,
  type FieldProps,
  type InputProps,
  type SelectProps,
  type TextareaProps,
} from '@fluentui/react-components'

/**
 * Kontrol form standar — pembungkus tipis `Field` + `Input`/`Select`/`Textarea`
 * Fluent, menggantikan pola `<label><span/>…<input/></label>` buatan tangan. Dengan
 * ini label, jarak, pesan bantuan/eror, dan gaya fokus semuanya datang dari TEMA
 * Fluent (bukan CSS per-elemen di index.css). `hint` = teks bantuan di bawah kontrol;
 * `validationMessage`/`validationState` untuk eror.
 */
type FieldExtras = {
  label?: FieldProps['label']
  hint?: FieldProps['hint']
  validationMessage?: FieldProps['validationMessage']
  validationState?: FieldProps['validationState']
  required?: boolean
}

export function TextField({
  label,
  hint,
  validationMessage,
  validationState,
  required,
  ...props
}: FieldExtras & InputProps) {
  return (
    <Field
      label={label}
      hint={hint}
      validationMessage={validationMessage}
      validationState={validationState}
      required={required}
    >
      <Input {...props} />
    </Field>
  )
}

export function SelectField({
  label,
  hint,
  validationMessage,
  validationState,
  required,
  children,
  ...props
}: FieldExtras & SelectProps) {
  return (
    <Field
      label={label}
      hint={hint}
      validationMessage={validationMessage}
      validationState={validationState}
      required={required}
    >
      <Select {...props}>{children}</Select>
    </Field>
  )
}

export function TextareaField({
  label,
  hint,
  validationMessage,
  validationState,
  required,
  ...props
}: FieldExtras & TextareaProps) {
  return (
    <Field
      label={label}
      hint={hint}
      validationMessage={validationMessage}
      validationState={validationState}
      required={required}
    >
      <Textarea {...props} />
    </Field>
  )
}
