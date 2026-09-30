import type { ReceiptEvidence } from '@/api/warehouse/receipts'

export const receiptLink = (id: string) => `/warehouse/receipts?id=${encodeURIComponent(id)}`
export function saveReceiptFile(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob), anchor = document.createElement('a')
  anchor.href = url; anchor.download = filename; anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export const evidenceLabel = (row: ReceiptEvidence) => `${row.contentType === 'application/pdf' ? 'PDF' : row.contentType === 'image/png' ? 'PNG' : 'JPEG'} · ${new Intl.DateTimeFormat('id-ID', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(row.createdAt))} · ${row.id.slice(0, 8)}`
