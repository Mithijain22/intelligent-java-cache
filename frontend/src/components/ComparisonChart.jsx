import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from 'recharts'

const POLICY_COLORS = {
  LRU: 'var(--lru-line)',
  LFU: 'var(--lfu-line)',
  ARC: 'var(--arc-line)',
  TinyLFU: 'var(--tinylfu-line)'
}

// Reads CSS custom properties at render time since Recharts fills need a
// real color value, not a var() reference, in some SVG contexts.
function resolveColor(varRef) {
  const name = varRef.match(/--[\w-]+/)?.[0]
  if (!name) return varRef
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || varRef
}

export default function ComparisonChart({ results, metric, onMetricChange }) {
  if (!results || Object.keys(results).length === 0) {
    return (
      <div className="panel" style={{ padding: 20 }}>
        <h3 className="panel-title" style={{ margin: 0 }}>
          Policy Comparison
        </h3>
        <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>
          Run a benchmark from the panel on the left to see policies compared side by side.
        </p>
      </div>
    )
  }

  const data = Object.values(results).map((r) => ({
    name: r.policyName,
    hitRate: +(r.hitRate * 100).toFixed(2),
    avgLatencyNs: +r.avgLatencyNanos.toFixed(0),
    p99LatencyNs: +r.p99LatencyNanos.toFixed(0),
    evictions: r.evictions,
    opsPerSecond: Math.round(r.opsPerSecond)
  }))

  const metrics = [
    { key: 'hitRate', label: 'Hit rate (%)' },
    { key: 'avgLatencyNs', label: 'Avg latency (ns)' },
    { key: 'p99LatencyNs', label: 'p99 latency (ns)' },
    { key: 'evictions', label: 'Evictions' },
    { key: 'opsPerSecond', label: 'Throughput (ops/sec)' }
  ]

  return (
    <div className="panel" style={{ padding: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 className="panel-title" style={{ margin: 0 }}>
          Policy Comparison — {Object.values(results)[0]?.traceLabel}
        </h3>
        <select
          value={metric}
          onChange={(e) => onMetricChange(e.target.value)}
          style={{
            background: 'var(--surface-raised)',
            color: 'var(--text)',
            border: '1px solid var(--border)',
            borderRadius: 4,
            padding: '4px 8px',
            fontSize: 12
          }}
        >
          {metrics.map((m) => (
            <option key={m.key} value={m.key}>
              {m.label}
            </option>
          ))}
        </select>
      </div>

      <div style={{ width: '100%', height: 260, marginTop: 12 }}>
        <ResponsiveContainer>
          <BarChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey="name" stroke="var(--text-muted)" tick={{ fontSize: 12, fontFamily: 'JetBrains Mono' }} />
            <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11, fontFamily: 'JetBrains Mono' }} />
            <Tooltip
              contentStyle={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', fontSize: 12 }}
              labelStyle={{ color: 'var(--text)' }}
            />
            <Bar dataKey={metric} radius={[3, 3, 0, 0]}>
              {data.map((entry) => (
                <Cell key={entry.name} fill={resolveColor(POLICY_COLORS[entry.name] || '--text-muted')} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
