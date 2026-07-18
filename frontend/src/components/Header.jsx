export default function Header({ connected, policy }) {
  return (
    <header
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '16px 24px',
        borderBottom: '1px solid var(--border)',
        background: 'var(--surface)'
      }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
        <span
          className="mono"
          style={{ fontSize: 15, fontWeight: 700, letterSpacing: '0.02em', color: 'var(--accent-amber)' }}
        >
          ▧ CACHE_BENCH
        </span>
        <span className="mono" style={{ fontSize: 12, color: 'var(--text-muted)' }}>
          intelligent-java-cache dashboard
        </span>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        {policy && (
          <span className="mono" style={{ fontSize: 12, color: 'var(--text-muted)' }}>
            live policy: <span style={{ color: 'var(--text)' }}>{policy}</span>
          </span>
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span
            style={{
              width: 8,
              height: 8,
              borderRadius: '50%',
              background: connected ? 'var(--hit-teal)' : 'var(--eviction-coral)',
              boxShadow: connected ? '0 0 6px var(--hit-teal)' : 'none'
            }}
          />
          <span className="mono" style={{ fontSize: 12, color: 'var(--text-muted)' }}>
            {connected ? 'live' : 'reconnecting…'}
          </span>
        </div>
      </div>
    </header>
  )
}
