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
 * ini label dan pesan bantuan/eror memakai semantik Fluent. Ukuran small dipadukan
 * dengan controls.css untuk densitas Azure. `hint` = teks bantuan di bawah kontrol;
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
  size = 'small',
  className,
  ...props
}: FieldExtras & InputProps) {
  return (
    <Field
      className="app-field"
      size={size}
      label={label}
      hint={hint}
      validationMessage={validationMessage}
      validationState={validationState}
      required={required}
    >
      <Input size={size} className={`app-control${size === 'small' ? ' app-control-compact' : ''}${className ? ` ${className}` : ''}`} {...props} />
    </Field>
  )
}

export function SelectField({
  label,
  hint,
  validationMessage,
  validationState,
  required,
  size = 'small',
  className,
  children,
  ...props
}: FieldExtras & SelectProps) {
  return (
    <Field
      className="app-field"
      size={size}
      label={label}
      hint={hint}
      validationMessage={validationMessage}
      validationState={validationState}
      required={required}
    >
      <Select size={size} className={`app-control${size === 'small' ? ' app-control-compact' : ''}${className ? ` ${className}` : ''}`} {...props}>{children}</Select>
    </Field>
  )
}

export function TextareaField({
  label,
  hint,
  validationMessage,
  validationState,
  required,
  size = 'small',
  className,
  ...props
}: FieldExtras & TextareaProps) {
  return (
    <Field
      className="app-field"
      size={size}
      label={label}
      hint={hint}
      validationMessage={validationMessage}
      validationState={validationState}
      required={required}
    >
      <Textarea size={size} className={`app-control${size === 'small' ? ' app-control-compact' : ''}${className ? ` ${className}` : ''}`} {...props} />
    </Field>
  )
}
