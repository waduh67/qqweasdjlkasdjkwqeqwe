import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import { NetworkResourceSections } from './NetworkResourceSections'
it('loads a section on demand and preserves its draft when switching sections', async () => {
  const sections = [{ key: 'overview', label: 'Ringkasan', content: <p>ODP Utara</p> }, { key: 'connections', label: 'Sambungan', content: <input aria-label="Catatan sambungan" /> }]
  const user = userEvent.setup(), view = render(<NetworkResourceSections label="Bagian perangkat" sections={sections} />)
  expect(screen.queryByRole('textbox')).toBeNull()
  await user.click(screen.getByRole('tab', { name: 'Sambungan' }))
  await user.type(screen.getByRole('textbox'), 'Core 1 ke splitter')
  await user.click(screen.getByRole('tab', { name: 'Ringkasan' }))
  expect(screen.queryByRole('textbox')).toBeNull()
  await user.click(screen.getByRole('tab', { name: 'Sambungan' }))
  expect(screen.getByRole('textbox')).toHaveProperty('value', 'Core 1 ke splitter')
  view.rerender(<NetworkResourceSections label="Bagian perangkat" sections={[sections[0]]} />)
  expect(screen.getByRole('tabpanel', { name: 'Ringkasan' })).toBeTruthy()
  expect(screen.queryByRole('textbox', { hidden: true })).toBeNull()
})
