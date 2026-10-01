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
  fieldClassName?: string
  fieldStyle?: FieldProps['style']
}

export function TextField({
  fieldClassName, fieldStyle,
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
      className={`app-field${fieldClassName ? ` ${fieldClassName}` : ''}`} style={fieldStyle}
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
  fieldClassName, fieldStyle,
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
      className={`app-field${fieldClassName ? ` ${fieldClassName}` : ''}`} style={fieldStyle}
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
  fieldClassName, fieldStyle,
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
      className={`app-field${fieldClassName ? ` ${fieldClassName}` : ''}`} style={fieldStyle}
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
