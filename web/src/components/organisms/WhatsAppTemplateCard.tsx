import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Pencil, Trash2 } from 'lucide-react'
import { typographyStyles } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  createTemplate,
  deleteTemplate,
  getTemplates,
  saveTemplateAssignments,
  syncTemplates,
  TEMPLATE_CATEGORY_LABEL,
  TEMPLATE_STATUS_LABEL,
  TRIGGERS,
  TRIGGER_LABEL,
  updateTemplate,
  type NotificationTemplateView,
  type NotificationTrigger,
  type TemplateCatalogView,
  type TemplateCategory,
  type TemplateStatus,
} from '@/api/notification'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, TextField, TextareaField, Toolbar } from '@/components/atoms'
import { IconAlert } from '@/components/atoms/icons'
import { ConfirmDialog, Modal } from '@/components/molecules'
import { useToast } from '@/system'
import { DataTable, type Column, type RowAction } from './DataTable'

/**
 * Kartu "Template pesan WhatsApp" di halaman Pengaturan Notifikasi.
 *
 * Terpisah dari kartu Gateway karena isinya bukan kredensial: template baru relevan
 * setelah gateway WhatsApp resmi (Meta Cloud / Mekari Qontak) hidup dengan kredensialnya
 * TERSIMPAN. Selama prasyarat itu belum terpenuhi, kartu tampil terkunci beserta alasannya
 * — bukan disembunyikan — supaya operator tahu apa yang harus dilengkapi.
 *
 * Katalog di sini adalah CERMIN template di penyedia: menambah berarti mengajukan template
 * sungguhan (statusnya lalu menunggu peninjauan), menghapus berarti menghapusnya di sana bila
 * penyedianya mengizinkan. Kemampuan itu berbeda per penyedia dan datang dari server lewat
 * `canEdit`/`canDeleteRemotely` — tombol yang pasti gagal tak ditawarkan sama sekali.
 *
 * Satu pemicu maksimal satu template (ditegakkan DB), tapi satu template boleh melayani
 * beberapa pemicu. Pemicu tanpa template dikirim sebagai teks biasa — kecuali di Qontak,
 * yang hanya menerima template sehingga pemicunya justru dilewati.
 */
export function WhatsAppTemplateCard({ templateReady }: { templateReady: boolean }) {
  const { can } = useCan()
  const toast = useToast()
  const canView = can('notification.template.view')
  const canManage = can('notification.template.manage')

  const [catalog, setCatalog] = useState<TemplateCatalogView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  // Form tambah/ubah; null = tertutup, id null = pengajuan baru.
  const [draft, setDraft] = useState<Draft | null>(null)
  const [pendingDelete, setPendingDelete] = useState<NotificationTemplateView | null>(null)
  // Pemetaan yang sedang disunting operator, terpisah dari yang tersimpan agar bisa dibatalkan.
  const [assignments, setAssignments] = useState<Partial<Record<NotificationTrigger, string>>>({})

  const load = useCallback(() => {
    if (!canView) return
    setLoading(true)
    void getTemplates()
      .then((c) => {
        setCatalog(c)
        setAssignments(c.assignments)
      })
      .catch(() => setCatalog(null))
      .finally(() => setLoading(false))
  }, [canView])

  useEffect(() => load(), [load])

  if (!canView) return null

  // Prasyarat dihitung dari setelan TERSIMPAN + jawaban server; keduanya harus sepakat
  // sebelum aksi ditawarkan (token yang baru diketik tapi belum disimpan belum membuka kartu).
  const unlocked = templateReady && (catalog?.manageable ?? false)
  const editable = unlocked && canManage
  const lockReason = catalog?.blockedReason ?? (templateReady ? null : 'Simpan setelan gateway di atas dulu.')
  const provider = catalog?.providerLabel ?? 'penyedia WhatsApp'
  const templateOnly = catalog?.requiresTemplateForEveryTrigger ?? false

  const run = async (action: () => Promise<TemplateCatalogView>, okMessage: string) => {
    setBusy(true)
    try {
      const next = await action()
      setCatalog(next)
      setAssignments(next.assignments)
      toast.success(okMessage)
      return true
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
      return false
    } finally {
      setBusy(false)
    }
  }

  const saveDraft = async () => {
    if (!draft) return
    // Divalidasi di sini juga (server tetap penentu) supaya kesalahan ketik ketahuan
    // sebelum sebuah pengajuan yang pasti ditolak dikirim ke penyedia.
    const problem = bodyProblem(draft.bodyText)
    if (problem) {
      toast.error(problem)
      return
    }
    const ok = draft.id
      ? await run(
          () => updateTemplate(draft.id as string, { category: draft.category, bodyText: draft.bodyText.trim() }),
          `Perubahan dikirim ke ${provider}; template masuk antrean peninjauan lagi.`,
        )
      : await run(
          () =>
            createTemplate({
              name: draft.name.trim(),
              language: draft.language.trim() || null,
              category: draft.category,
              bodyText: draft.bodyText.trim(),
            }),
          `Template diajukan ke ${provider}; statusnya menunggu tinjauan sampai disetujui.`,
        )
    if (ok) setDraft(null)
  }

  const removeTemplate = async () => {
    if (!pendingDelete) return
    setBusy(true)
    try {
      const result = await deleteTemplate(pendingDelete.id)
      setCatalog(result.catalog)
      setAssignments(result.catalog.assignments)
      // Pesan server yang dipakai: hanya server yang tahu template itu benar-benar
      // ikut terhapus di penyedia atau cuma hilang dari daftar aplikasi.
      toast.success(result.message)
      setPendingDelete(null)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus template')
    } finally {
      setBusy(false)
    }
  }

  const sync = async () => {
    setBusy(true)
    try {
      const result = await syncTemplates()
      setCatalog(result.catalog)
      setAssignments(result.catalog.assignments)
      toast.success(result.message)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : `Gagal menarik template dari ${provider}`)
    } finally {
      setBusy(false)
    }
  }

  const templates = catalog?.templates ?? []
  const byId = new Map(templates.map((t) => [t.id, t]))

  const columns: Column<NotificationTemplateView>[] = [
    {
      key: 'name',
      header: 'Nama',
      cell: (t) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <code>{t.name}</code>
          {t.bodyText && (
            <span className="muted" style={{ ...typographyStyles.caption1 }}>
              {t.bodyText.length > 90 ? `${t.bodyText.slice(0, 90)}…` : t.bodyText}
            </span>
          )}
        </div>
      ),
      sortValue: (t) => t.name,
    },
    { key: 'language', header: 'Bahasa', cell: (t) => t.language, sortValue: (t) => t.language },
    {
      key: 'status',
      header: 'Status',
      cell: (t) => <Badge tone={STATUS_TONE[t.status]}>{TEMPLATE_STATUS_LABEL[t.status]}</Badge>,
      sortValue: (t) => t.status,
    },
    {
      key: 'params',
      header: 'Parameter',
      align: 'right',
      // Dispatcher selalu mengirim TEPAT SATU parameter ({{1}} = seluruh pesan); jumlah lain
      // akan ditolak penyedia saat kirim, jadi ditandai di sini alih-alih saat pesan gagal.
      cell: (t) =>
        t.bodyParamCount === 1 || t.status === 'UNKNOWN' ? (
          <span className="tnum">{t.bodyParamCount}</span>
        ) : (
          <Badge tone="warning">{t.bodyParamCount} ≠ 1</Badge>
        ),
      sortValue: (t) => t.bodyParamCount,
    },
    {
      key: 'usedBy',
      header: 'Dipakai untuk',
      cell: (t) =>
        t.usedBy.length === 0 ? (
          <span className="muted">—</span>
        ) : (
          <div className="row" style={{ gap: '0.25rem', flexWrap: 'wrap' }}>
            {t.usedBy.map((trigger) => (
              <Badge key={trigger}>{TRIGGER_LABEL[trigger] ?? trigger}</Badge>
            ))}
          </div>
        ),
    },
    {
      key: 'syncedAt',
      header: 'Terakhir sinkron',
      cell: (t) => (t.syncedAt ? new Date(t.syncedAt).toLocaleString('id-ID') : <span className="muted">—</span>),
      sortValue: (t) => t.syncedAt ?? '',
    },
  ]

  // Tombol "Ubah" hanya muncul bila penyedianya memang punya API sunting — Qontak tidak,
  // dan menawarkan tombol yang selalu berujung 409 cuma memancing operator mencobanya.
  const rowActions = (t: NotificationTemplateView): RowAction[] => {
    if (!editable) return []
    const actions: RowAction[] = []
    if (catalog?.canEdit) {
      actions.push({
        key: 'edit',
        label: 'Ubah isi',
        icon: <Pencil size={16} />,
        onClick: () =>
          setDraft({
            id: t.id,
            name: t.name,
            language: t.language,
            category: (t.category as TemplateCategory) ?? 'UTILITY',
            bodyText: t.bodyText ?? '',
          }),
      })
    }
    actions.push({ key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => setPendingDelete(t) })
    return actions
  }

  const assignmentsDirty =
    JSON.stringify(normalize(assignments)) !== JSON.stringify(normalize(catalog?.assignments ?? {}))

  return (
    <div className="card stack" aria-disabled={!unlocked}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <SectionTitle>Template pesan WhatsApp</SectionTitle>
        {catalog && <Badge tone={unlocked ? 'good' : 'neutral'}>{templates.length} template</Badge>}
      </div>


      {!unlocked && lockReason && <Callout>{lockReason}</Callout>}

      {editable && (
        <Toolbar>
          <Button variant="primary" disabled={busy || !catalog?.syncable} onClick={() => void sync()}>
            {busy ? 'Memproses…' : `Tarik dari ${provider}`}
          </Button>
          <Button
            variant="subtle"
            disabled={busy}
            onClick={() => setDraft({ id: null, name: '', language: '', category: 'UTILITY', bodyText: '' })}
          >
            Ajukan template baru
          </Button>
        </Toolbar>
      )}

      <DataTable
        columns={columns}
        rows={templates}
        rowKey={(t) => t.id}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        rowActions={editable ? rowActions : undefined}
        empty={<EmptyState title="Belum ada template" icon={<IconAlert size={28} />} />}
      />

      {/* ---- Pemakaian per pemicu ---- */}
      <SectionTitle>Pemakaian per pemicu</SectionTitle>
      {templateOnly && (
        <p className="muted" style={{ margin: 0, ...typographyStyles.body1 }}>
          {provider} <strong>hanya bisa mengirim template</strong> — pemicu tanpa template akan dilewati, pesannya tak sampai sama sekali.
        </p>
      )}


      {TRIGGERS.map((trigger) => {
        const selected = assignments[trigger] ? byId.get(assignments[trigger] as string) : undefined
        return (
          <div key={trigger} className="stack" style={{ gap: '0.2rem' }}>
            <SelectField
              label={TRIGGER_LABEL[trigger]}
              value={assignments[trigger] ?? ''}
              onChange={(_, data) =>
                setAssignments((prev) => {
                  const next = { ...prev }
                  if (data.value) next[trigger] = data.value
                  else delete next[trigger]
                  return next
                })
              }
              disabled={!editable || busy}
            >
              <option value="">{templateOnly ? '— tidak dikirim —' : '— teks biasa —'}</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} ({t.language})
                </option>
              ))}
            </SelectField>
            {!selected && templateOnly && (
              <span style={{ ...typographyStyles.caption1, color: 'var(--critical)' }}>
                Belum dipetakan — pemicu ini tak akan mengirim apa pun lewat {provider}.
              </span>
            )}
            {selected && selected.status !== 'APPROVED' && (
              <span className="muted" style={{ ...typographyStyles.caption1 }}>
                Status template ini {TEMPLATE_STATUS_LABEL[selected.status].toLowerCase()} — {provider} bisa menolak
                pengiriman sampai disetujui.
              </span>
            )}
            {selected && selected.status === 'APPROVED' && selected.bodyParamCount !== 1 && (
              <span className="muted" style={{ ...typographyStyles.caption1 }}>
                Template ini punya {selected.bodyParamCount} parameter, sedangkan sistem selalu mengirim tepat satu
                ({'{{1}}'} = seluruh isi pesan).
              </span>
            )}
          </div>
        )
      })}

      {editable && (
        <div className="row" style={{ gap: '0.5rem' }}>
          <Button variant="primary" disabled={busy || !assignmentsDirty} onClick={() => void run(() => saveTemplateAssignments(assignments), 'Pemakaian template disimpan')}>
            Simpan pemakaian
          </Button>
          {assignmentsDirty && (
            <Button variant="subtle" disabled={busy} onClick={() => setAssignments(catalog?.assignments ?? {})}>
              Batalkan perubahan
            </Button>
          )}
        </div>
      )}

      {draft && (
        <Modal
          title={draft.id ? 'Ubah isi template' : `Ajukan template baru ke ${provider}`}
          onClose={() => !busy && setDraft(null)}
          footer={
            <>
              <Button variant="subtle" onClick={() => setDraft(null)} disabled={busy}>
                Batal
              </Button>
              <Button
                variant="primary"
                onClick={() => void saveDraft()}
                disabled={busy || !draft.name.trim() || !draft.bodyText.trim()}
              >
                {busy ? 'Mengirim…' : draft.id ? 'Kirim perubahan' : 'Ajukan'}
              </Button>
            </>
          }
        >
          <div className="stack">
            <TextField
              label="Nama template"
              value={draft.name}
              onChange={(_, data) => setDraft({ ...draft, name: data.value })}
              placeholder="tagihan_jatuh_tempo"
              disabled={!!draft.id}
              hint={
                draft.id
                  ? `Nama tak bisa diubah setelah template diajukan — begitu aturan ${provider}. Ganti nama = hapus lalu ajukan baru.`
                  : 'Huruf kecil, angka, dan garis bawah saja.'
              }
            />
            <TextField
              label="Bahasa"
              value={draft.language}
              onChange={(_, data) => setDraft({ ...draft, language: data.value })}
              placeholder="id"
              disabled={!!draft.id}
              hint={draft.id ? 'Terkunci bersama nama.' : 'Kosongkan untuk id. Contoh lain: en, en_US.'}
            />
            <SelectField
              label="Kategori"
              value={draft.category}
              onChange={(_, data) => setDraft({ ...draft, category: data.value as TemplateCategory })}
            >
              {(Object.keys(TEMPLATE_CATEGORY_LABEL) as TemplateCategory[]).map((c) => (
                <option key={c} value={c}>
                  {TEMPLATE_CATEGORY_LABEL[c]}
                </option>
              ))}
            </SelectField>
            <TextareaField
              label="Isi pesan"
              value={draft.bodyText}
              onChange={(_, data) => setDraft({ ...draft, bodyText: data.value })}
              placeholder="Halo, ada info dari kami: {{1}}"
              rows={4}
              validationState={draft.bodyText.trim() && bodyProblem(draft.bodyText) ? 'error' : 'none'}
              validationMessage={draft.bodyText.trim() ? (bodyProblem(draft.bodyText) ?? undefined) : undefined}
              hint={`Wajib memuat tepat satu variabel {{1}} — variabel itulah yang diisi seluruh isi notifikasi saat pesan dikirim. Maks ${MAX_BODY} karakter.`}
            />
            <p className="muted" style={{ margin: 0, ...typographyStyles.body1 }}>
              {draft.id
                ? `Suntingan dikirim ke ${provider} dan template kembali masuk antrean peninjauan.`
                : `Template dikirim ke ${provider} untuk ditinjau. Sampai disetujui, statusnya “menunggu tinjauan” dan belum bisa dipakai mengirim.`}
            </p>
          </div>
        </Modal>
      )}

      {pendingDelete && (
        <ConfirmDialog
          title="Hapus template"
          message={
            <>
              <p style={{ margin: 0 }}>
                Hapus <code>{pendingDelete.name}</code> ({pendingDelete.language})?
              </p>
              <p style={{ margin: 0 }}>
                {catalog?.canDeleteRemotely
                  ? `Template ini ikut dihapus di ${provider} dan tak bisa dikembalikan.`
                  : `${provider} tak menyediakan API hapus — template hanya hilang dari daftar aplikasi dan TETAP ADA di sana. Hapus juga lewat dasbornya bila tak ingin terpakai lagi.`}
              </p>
              {pendingDelete.usedBy.length > 0 && (
                <p style={{ margin: 0 }}>
                  {pendingDelete.usedBy.length} pemicu yang memakainya akan{' '}
                  {templateOnly ? 'berhenti mengirim pesan' : 'kembali mengirim pesan teks biasa'}.
                </p>
              )}
            </>
          }
          confirmLabel="Hapus"
          danger
          busy={busy}
          onConfirm={() => void removeTemplate()}
          onClose={() => setPendingDelete(null)}
        />
      )}
    </div>
  )
}

type Draft = {
  id: string | null
  name: string
  language: string
  category: TemplateCategory
  bodyText: string
}

const MAX_BODY = 1024

/**
 * Cermin `NotificationMessageTemplate.validateBody` di server — server tetap penentu, ini
 * hanya agar kesalahan ketik ketahuan sebelum pengajuan yang pasti ditolak dikirim ke
 * penyedia. Aturannya milik Meta/Qontak: tepat satu jenis variabel, tanpa baris kosong,
 * tab, atau lebih dari empat spasi beruntun.
 */
function bodyProblem(bodyText: string): string | null {
  const text = bodyText.trim()
  if (!text) return 'Isi pesan wajib diisi'
  if (text.length > MAX_BODY) return `Isi pesan maksimal ${MAX_BODY} karakter`
  const indices = new Set(Array.from(text.matchAll(/\{\{\s*(\d+)\s*\}\}/g), (m) => m[1]))
  if (indices.size !== 1 || !indices.has('1')) {
    return 'Isi pesan harus memuat tepat satu jenis variabel {{1}}'
  }
  if (text.includes('\n\n') || text.includes('\t') || text.includes('     ')) {
    return 'Tak boleh ada baris kosong, tab, atau lebih dari empat spasi beruntun'
  }
  return null
}

const STATUS_TONE: Record<TemplateStatus, 'neutral' | 'good' | 'warning' | 'serious' | 'critical'> = {
  APPROVED: 'good',
  PENDING: 'warning',
  REJECTED: 'critical',
  PAUSED: 'serious',
  DISABLED: 'neutral',
  UNKNOWN: 'neutral',
}

/** Buang entri kosong agar perbandingan "ada perubahan?" tak terusik urutan kunci. */
function normalize(map: Partial<Record<NotificationTrigger, string>>): [string, string][] {
  return Object.entries(map)
    .filter(([, id]) => !!id)
    .sort(([a], [b]) => a.localeCompare(b)) as [string, string][]
}

/** Kotak peringatan bernada amber — seragam dengan kartu terkunci di Pengaturan Pembayaran. */
function Callout({ children }: { children: ReactNode }) {
  return (
    <div
      className="row"
      style={{
        gap: '0.5rem',
        alignItems: 'flex-start',
        padding: '0.6rem 0.75rem',
        borderRadius: 'var(--radius-sm)',
        background: 'color-mix(in srgb, var(--warning) 12%, var(--surface))',
        border: '1px solid color-mix(in srgb, var(--warning) 32%, transparent)',
        ...typographyStyles.body1,
      }}
    >
      <IconAlert size={16} />
      <span>{children}</span>
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return <h3 style={{ margin: '0.25rem 0 0', ...typographyStyles.subtitle2 }}>{children}</h3>
}
