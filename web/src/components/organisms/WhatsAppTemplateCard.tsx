import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Pencil, Trash2 } from 'lucide-react'
import { ApiError } from '@/api/client'
import {
  createTemplate,
  deleteTemplate,
  getTemplates,
  saveTemplateAssignments,
  syncTemplates,
  TEMPLATE_STATUS_LABEL,
  TRIGGERS,
  TRIGGER_LABEL,
  updateTemplate,
  type NotificationTemplateView,
  type NotificationTrigger,
  type TemplateCatalogView,
  type TemplateStatus,
} from '@/api/notification'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, TextField, Toolbar } from '@/components/atoms'
import { IconAlert } from '@/components/atoms/icons'
import { ConfirmDialog, Modal } from '@/components/molecules'
import { useToast } from '@/system'
import { DataTable, type Column, type RowAction } from './DataTable'

/**
 * Kartu "Template pesan WhatsApp" di halaman Pengaturan Notifikasi.
 *
 * Terpisah dari kartu Gateway karena isinya bukan kredensial: template baru relevan
 * setelah gateway WhatsApp resmi (Meta Cloud) hidup dengan Phone Number ID + access token
 * TERSIMPAN. Selama prasyarat itu belum terpenuhi, kartu tampil terkunci beserta alasannya
 * — bukan disembunyikan — supaya operator tahu apa yang harus dilengkapi.
 *
 * Template dibuat & disetujui di Meta Business Manager; di sini hanya katalog lokal supaya
 * tiap pemicu otomatis bisa ditunjuk satu template. Satu pemicu maksimal satu template
 * (ditegakkan DB), tapi satu template boleh melayani beberapa pemicu. Pemicu tanpa template
 * dikirim sebagai teks biasa — perilaku lama, bukan gagal kirim.
 */
export function WhatsAppTemplateCard({ templateReady }: { templateReady: boolean }) {
  const { can } = useCan()
  const toast = useToast()
  const canView = can('notification.template.view')
  const canManage = can('notification.template.manage')

  const [catalog, setCatalog] = useState<TemplateCatalogView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  // Form tambah/ubah manual; null = tertutup, id null = entri baru.
  const [draft, setDraft] = useState<{ id: string | null; name: string; language: string } | null>(null)
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
    const body = { name: draft.name.trim(), language: draft.language.trim() || null }
    const ok = await run(
      () => (draft.id ? updateTemplate(draft.id, body) : createTemplate(body)),
      draft.id ? 'Template diperbarui' : 'Template ditambahkan',
    )
    if (ok) setDraft(null)
  }

  const removeTemplate = async () => {
    if (!pendingDelete) return
    const ok = await run(() => deleteTemplate(pendingDelete.id), 'Template dihapus dari katalog')
    if (ok) setPendingDelete(null)
  }

  const sync = async () => {
    setBusy(true)
    try {
      const result = await syncTemplates()
      setCatalog(result.catalog)
      setAssignments(result.catalog.assignments)
      toast.success(result.message)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menarik template dari Meta')
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
          {t.bodyPreview && (
            <span className="muted" style={{ fontSize: '0.78rem' }}>
              {t.bodyPreview.length > 90 ? `${t.bodyPreview.slice(0, 90)}…` : t.bodyPreview}
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
      // akan ditolak Meta saat kirim, jadi ditandai di sini alih-alih saat pesan gagal.
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

  const rowActions = (t: NotificationTemplateView): RowAction[] =>
    editable
      ? [
          {
            key: 'edit',
            label: 'Ubah',
            icon: <Pencil size={16} />,
            onClick: () => setDraft({ id: t.id, name: t.name, language: t.language }),
          },
          { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => setPendingDelete(t) },
        ]
      : []

  const assignmentsDirty =
    JSON.stringify(normalize(assignments)) !== JSON.stringify(normalize(catalog?.assignments ?? {}))

  return (
    <div className="card stack" aria-disabled={!unlocked}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <SectionTitle>Template pesan WhatsApp</SectionTitle>
        {catalog && <Badge tone={unlocked ? 'good' : 'neutral'}>{templates.length} template</Badge>}
      </div>

      <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
        Template <em>utility</em> dibuat &amp; disetujui di Meta Business Manager. Di sini Anda menarik daftarnya lalu
        menentukan template mana dipakai tiap pemicu otomatis — satu pemicu satu template.
      </p>

      {!unlocked && lockReason && <Callout>{lockReason}</Callout>}

      {editable && (
        <Toolbar>
          <Button
            variant="primary"
            disabled={busy || !catalog?.syncable}
            title={catalog?.syncable ? undefined : 'Isi WhatsApp Business Account ID di kartu Gateway lalu simpan'}
            onClick={() => void sync()}
          >
            {busy ? 'Memproses…' : 'Tarik dari Meta'}
          </Button>
          <Button variant="subtle" disabled={busy} onClick={() => setDraft({ id: null, name: '', language: '' })}>
            Tambah manual
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
        empty={
          <EmptyState
            title="Belum ada template"
            hint={
              unlocked
                ? 'Tarik dari Meta untuk memuat template utility yang sudah disetujui.'
                : 'Lengkapi prasyarat di atas untuk mulai mengelola template.'
            }
            icon={<IconAlert size={28} />}
          />
        }
      />

      {/* ---- Pemakaian per pemicu ---- */}
      <SectionTitle>Pemakaian per pemicu</SectionTitle>
      <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
        Pemicu tanpa template dikirim sebagai pesan teks biasa — hanya sampai bila pelanggan membalas dalam 24 jam
        terakhir. Satu template boleh dipakai beberapa pemicu.
      </p>

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
              <option value="">— teks biasa —</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} ({t.language})
                </option>
              ))}
            </SelectField>
            {selected && selected.status !== 'APPROVED' && (
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                Status template ini {TEMPLATE_STATUS_LABEL[selected.status].toLowerCase()} — Meta bisa menolak
                pengiriman sampai disetujui.
              </span>
            )}
            {selected && selected.status === 'APPROVED' && selected.bodyParamCount !== 1 && (
              <span className="muted" style={{ fontSize: '0.8rem' }}>
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
          title={draft.id ? 'Ubah template' : 'Tambah template manual'}
          onClose={() => !busy && setDraft(null)}
          footer={
            <>
              <Button variant="subtle" onClick={() => setDraft(null)} disabled={busy}>
                Batal
              </Button>
              <Button variant="primary" onClick={() => void saveDraft()} disabled={busy || !draft.name.trim()}>
                {busy ? 'Menyimpan…' : 'Simpan'}
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
              hint="Persis seperti di Meta: huruf kecil, angka, dan garis bawah."
            />
            <TextField
              label="Bahasa"
              value={draft.language}
              onChange={(_, data) => setDraft({ ...draft, language: data.value })}
              placeholder="id"
              hint="Kosongkan untuk id. Contoh lain: en, en_US."
            />
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Entri manual tak memeriksa apa pun ke Meta — statusnya “belum disinkron” sampai Anda menekan “Tarik dari
              Meta”.
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
                Hapus <code>{pendingDelete.name}</code> ({pendingDelete.language}) dari katalog? Template di Meta tidak
                ikut terhapus.
              </p>
              {pendingDelete.usedBy.length > 0 && (
                <p style={{ margin: 0 }}>
                  {pendingDelete.usedBy.length} pemicu yang memakainya akan kembali mengirim pesan teks biasa.
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
        fontSize: '0.85rem',
      }}
    >
      <IconAlert size={16} />
      <span>{children}</span>
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return <h3 style={{ margin: '0.25rem 0 0', fontSize: '0.95rem', fontWeight: 600 }}>{children}</h3>
}
