import { useEffect, useRef } from 'react'

/** Coalesce draft changes without submitting on mount or losing the focused control. */
export function useAutoFilter<T>(value: T, onChange: (value: T) => void) {
  const key = JSON.stringify(value)
  const previous = useRef(key)
  const current = useRef({ value, onChange }); current.current = { value, onChange }
  useEffect(() => {
    if (key === previous.current) return
    previous.current = key
    current.current.onChange(current.current.value)
  }, [key])
}
