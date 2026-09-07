import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { PaymentPaidPage } from './PaymentReturnPage'

describe('PaymentPaidPage', () => {
  it('shows a close-page notice instead of navigation after payment is received', () => {
    render(
      <MemoryRouter>
        <PaymentPaidPage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Pembayaran diterima' })).toBeDefined()
    expect(screen.getByTestId('payment-close-notice')).toBeDefined()
    expect(screen.queryAllByRole('link')).toHaveLength(0)
  })
})
