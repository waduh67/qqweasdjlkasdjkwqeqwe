import { afterEach, describe, expect, it, vi } from 'vitest'

const { blob, post, postForm } = vi.hoisted(() => ({ blob: vi.fn(), post: vi.fn(), postForm: vi.fn() }))

vi.mock('./client', () => ({ api: { blob, post, postForm } }))

import {
  commitCustomerImport,
  downloadCustomerImportReport,
  importCustomers,
  retryCustomerImport,
  stageCustomerImport,
} from './onboarding'

describe('kontrak impor pelanggan bertahap', () => {
  it('mempertahankan mutasi JSON lama untuk pemanggil kompatibel', () => {
    importCustomers({ rows: [] })

    expect(post).toHaveBeenCalledWith('/api/onboarding/import/customers', { rows: [] })
  })

  it('mengunggah CSV multipart dengan operasi unggah dan mode yang dipilih', () => {
    const file = new File(['name'], 'pelanggan.csv', { type: 'text/csv' })

    stageCustomerImport(file, 'upload-key', 'ALREADY_INSTALLED')

    expect(postForm).toHaveBeenCalledWith(
      '/api/onboarding/v1/import/customers?operationKey=upload-key&mode=ALREADY_INSTALLED',
      expect.any(FormData),
    )
  })

  it('mengirim identitas commit yang sama ke commit dan replay retry', () => {
    const identity = { commitOperationKey: 'commit-key', commitHash: 'a'.repeat(64) }

    commitCustomerImport('batch-1', identity)
    retryCustomerImport('batch-1', identity)

    expect(post).toHaveBeenNthCalledWith(1, `/api/onboarding/v1/import/customers/batch-1/commit?commitOperationKey=commit-key&commitHash=${identity.commitHash}`)
    expect(post).toHaveBeenNthCalledWith(2, `/api/onboarding/v1/import/customers/batch-1/retry?commitOperationKey=commit-key&commitHash=${identity.commitHash}`)
  })

  it('mengambil rekap aman dari endpoint server terautentikasi', () => {
    downloadCustomerImportReport('batch-1')

    expect(blob).toHaveBeenCalledWith('/api/onboarding/v1/import/customers/batch-1/report')
  })
})

afterEach(() => {
  blob.mockReset()
  post.mockReset()
  postForm.mockReset()
})
