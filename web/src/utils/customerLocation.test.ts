import { expect, it } from 'vitest'
import { optionalCustomerLocation } from './customerLocation'
import { customerStatusLabel, hasCustomerLocation } from '@/api/network'

it('keeps an omitted location empty and preserves an explicitly chosen location', () => {
  expect(optionalCustomerLocation(' ', '')).toEqual({ location: null, error: null })
  expect(optionalCustomerLocation('108.562168', '-6.728143')).toEqual({ location: { longitude: 108.562168, latitude: -6.728143 }, error: null })
  expect(hasCustomerLocation(null)).toBe(false)
  expect(hasCustomerLocation({ longitude: 0, latitude: 0 })).toBe(false)
})
it.each([['108', ''], ['', '-6'], ['invalid', '-6'], ['Infinity', '-6'], ['181', '-6'], ['108', '91'], ['0', '0']])('rejects invalid coordinates %s, %s', (lng, lat) => {
  expect(optionalCustomerLocation(lng, lat).error).toBeTruthy()
})
it('shows one lifecycle label while preserving active, suspended and terminated states', () => {
  expect(customerStatusLabel('PROSPECT', true)).toBe('Menunggu instalasi')
  expect(customerStatusLabel('PROSPECT', false)).toBe('Prospek')
  expect(customerStatusLabel('ACTIVE', true)).toBe('Aktif')
  expect(customerStatusLabel('SUSPENDED', true)).toBe('Ditangguhkan')
  expect(customerStatusLabel('TERMINATED', true)).toBe('Berhenti')
})
