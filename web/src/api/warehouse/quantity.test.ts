import { describe, expect, it } from 'vitest'
import { formatBaseQuantity, quantityFromInput } from './quantity'

describe('warehouse quantity boundary', () => {
  it.each(['82.5', '82,5', '82.500', '82,500'])('converts measured %s metres exactly', (input) => {
    expect(quantityFromInput(input, 'MM')).toBe('82500')
  })

  it('retains the maximum bigint value through metre input and display', () => {
    expect(quantityFromInput('9223372036854775.807', 'MM')).toBe('9223372036854775807')
    expect(formatBaseQuantity('9223372036854775807', 'MM')).toBe('9223372036854775,807')
    expect(quantityFromInput('9223372036854775807', 'EA')).toBe('9223372036854775807')
    expect(formatBaseQuantity('18446744073709551614', 'EA')).toBe('18446744073709551614')
  })

  it.each(['17.5000', '1.000,5', '1,000.5', '-1', '+1', '1e3', '01', '', '0', '9223372036854775.808'])('rejects malformed or overflowing metres %s', (input) => {
    expect(() => quantityFromInput(input, 'MM')).toThrow()
  })

  it.each(['1.0', '0.5', '1,5', '9223372036854775808'])('rejects fractional or overflowing items %s', (input) => {
    expect(() => quantityFromInput(input, 'EA')).toThrow()
  })

  it('keeps explicit zero and signed ledger deltas without rounding', () => {
    expect(quantityFromInput('0', 'MM', true)).toBe('0')
    expect(formatBaseQuantity('-1', 'MM')).toBe('-0,001')
    expect(formatBaseQuantity('17500', 'MM', '.')).toBe('17.500')
  })
})
