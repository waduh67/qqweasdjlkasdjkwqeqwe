/** Baris skeleton untuk keadaan memuat tabel. */
export function SkeletonRows({ rows = 4, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="row" style={{ gap: '0.75rem' }}>
          {Array.from({ length: cols }).map((_, c) => (
            <div
              key={c}
              className="skeleton"
              style={{ height: 14, flex: c === 0 ? 2 : 1, borderRadius: 6 }}
            />
          ))}
        </div>
      ))}
    </div>
  )
}
