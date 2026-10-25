const publicPaths = ['/login', '/signup', '/paid', '/failed', '/expired', '/portal', '/hotspot-portal', '/bayar'] as const

export function isInternalPath(pathname: string): boolean {
  return !publicPaths.some(path => pathname === path || pathname.startsWith(path + '/'))
}
