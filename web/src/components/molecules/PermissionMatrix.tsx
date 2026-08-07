import { useMemo } from 'react'
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
              <input
                type="checkbox"
                style={{ width: 'auto' }}
                checked={allChecked}
                disabled={disabled}
                onChange={(e) => toggle(allIds, e.target.checked)}
                aria-label={`Pilih semua izin ${module.name}`}
              />
              <h4>{module.name}</h4>
            </div>

            <table className="matrix">
              <thead>
                <tr>
                  <th style={{ width: '30%' }}>Resource</th>
                  {module.actions.map((action) => (
                    <th key={action}>{action}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {module.resources.map((resource) => (
                  <tr key={resource}>
                    <td className="resource">{resource}</td>
                    {module.actions.map((action) => {
                      const permission = module.byKey.get(`${resource}.${action}`)
                      return (
                        <td key={action}>
                          {permission ? (
                            <input
                              type="checkbox"
                              checked={selected.has(permission.id)}
                              disabled={disabled}
                              onChange={(e) => toggle([permission.id], e.target.checked)}
                              title={permission.description ?? permission.code}
                              aria-label={permission.code}
                            />
                          ) : (
                            <span className="muted">–</span>
                          )}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )
      })}
    </div>
  )
}
