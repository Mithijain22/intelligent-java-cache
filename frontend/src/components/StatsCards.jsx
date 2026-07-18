export default function StatsCards({ stats }) {
  if (!stats) {
    return (
      <div className="panel" style={{ padding: 20 }}>
        <h3 className="panel-title" style={{ margin: 0 }}>
          Live Stats
        </h3>
        <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>Waiting for first update…</p>
      </div>
    )
  }

  const cards = [
    { label: 'hit rate', value: `${(stats.hitRate * 100).toFixed(1)}%`, color: 'var(--hit-teal)' },
    { label: 'size', value: stats.size, color: 'var(--text)' },
    { label: 'hits', value: stats.hits, color: 'var(--hit-teal)' },
    { label: 'misses', value: stats.misses, color: 'var(--miss-amber)' },
    { label: 'evictions', value: stats.evictions, color: 'var(--eviction-coral)' },
    { label: 'expirations', value: stats.expirations, color: 'var(--text-muted)' }
  ]

  return (
    <div className="panel" style={{ padding: 20 }}>
      <h3 className="panel-title" style={{ margin: 0 }}>
        Live Stats — {stats.policy}
      </h3>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(100px, 1fr))',
          gap: 12,
          marginTop: 14
        }}
      >
        {cards.map((card) => (
          <div
            key={card.label}
            style={{
              background: 'var(--surface-raised)',
              border: '1px solid var(--border)',
              borderRadius: 4,
              padding: '10px 12px'
            }}
          >
            <div className="mono" style={{ fontSize: 20, fontWeight: 600, color: card.color }}>
              {card.value}
            </div>
            <div className="mono" style={{ fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              {card.label}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
