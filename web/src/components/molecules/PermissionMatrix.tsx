import { useMemo } from 'react'
import { Checkbox, Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text, typographyStyles } from '@fluentui/react-components'
import type { PermissionCatalog } from '@/api/types'

interface Props {
  catalog: PermissionCatalog
  selected: Set<string>
  onChange: (next: Set<string>) => void
  disabled?: boolean
}

/**
 * Matriks izin per module: baris = resource, kolom = action.
 *
 * Bentuk ini mungkin karena kode izin terstruktur `module.resource.action`,
 * sehingga permukaan RBAC bisa dirender otomatis — menambah izin baru di katalog
 * server langsung muncul di sini tanpa mengubah UI.
 */
export function PermissionMatrix({ catalog, selected, onChange, disabled }: Props) {
  const modules = useMemo(
    () =>
      catalog.modules.map((module) => {
        const actions = [...new Set(module.permissions.map((p) => p.action))].sort()
        const resources = [...new Set(module.permissions.map((p) => p.resource))].sort()
        const byKey = new Map(module.permissions.map((p) => [`${p.resource}.${p.action}`, p]))
        return { name: module.module, actions, resources, byKey, permissions: module.permissions }
      }),
    [catalog],
  )

  function toggle(ids: string[], checked: boolean) {
    const next = new Set(selected)
    for (const id of ids) {
      if (checked) next.add(id)
      else next.delete(id)
    }
    onChange(next)
  }

  return (
    <div>
      {modules.map((module) => {
        const allIds = module.permissions.map((p) => p.id)
        const allChecked = allIds.every((id) => selected.has(id))

        return (
          <section className="matrix-module" key={module.name}>
            <div className="row">
              <Checkbox
                checked={allChecked}
                disabled={disabled}
                onChange={(_, data) => toggle(allIds, data.checked === true)}
                aria-label={`Pilih semua izin ${module.name}`}
              />
              <h4 style={typographyStyles.subtitle2}>{module.name}</h4>
            </div>

            <Table className="matrix"><TableHeader><TableRow ><TableHeaderCell style={{ width: '30%' }}>Resource</TableHeaderCell>
            {module.actions.map((action) => (
              <TableHeaderCell key={action}>{action}</TableHeaderCell>
            ))}</TableRow></TableHeader>
            <TableBody>{module.resources.map((resource) => (
              <TableRow key={resource}><TableCell className="resource">{resource}</TableCell>
              {module.actions.map((action) => {
                const permission = module.byKey.get(`${resource}.${action}`)
                return (
                  <TableCell key={action}>{permission ? (
                    <Checkbox
                      checked={selected.has(permission.id)}
                      disabled={disabled}
                      onChange={(_, data) => toggle([permission.id], data.checked === true)}
                      title={permission.description ?? permission.code}
                      aria-label={permission.code}
                    />
                  ) : (
                    <Text as="span" className="muted" size={200}>–</Text>
                  )}</TableCell>
                )
              })}</TableRow>
            ))}</TableBody></Table>
          </section>
        )
      })}
    </div>
  )
}
