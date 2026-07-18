import { useState } from 'react'
import { getEntry, putEntry, deleteEntry } from '../api.js'

/**
 * Manual REST tester for the live cache. Every action here fires a local
 * flash on the Memory Bank Grid immediately (no need to wait for the
 * WebSocket's once-a-second stats broadcast) -- eviction flashes still come
 * from the real server-pushed event via useLiveStats, since evictions are a
 * decision the cache makes, not something this panel can know locally.
 */
export default function LiveCacheTester({ onFlash, onEntriesChanged }) {
  const [key, setKey] = useState('')
  const [value, setValue] = useState('')
  const [ttl, setTtl] = useState('')
  const [lastResult, setLastResult] = useState(null)
  const [busy, setBusy] = useState(false)

  async function handlePut() {
    if (!key) return
    setBusy(true)
    try {
      await putEntry(key, value, ttl ? Number(ttl) : null)
      onFlash(key, 'miss') // a put always "writes" -- visually distinct from a read-hit
      onEntriesChanged()
      setLastResult(`put ${key} = ${value}`)
    } catch (err) {
      setLastResult(`error: ${err.message}`)
    } finally {
      setBusy(false)
    }
  }

  async function handleGet() {
    if (!key) return
    setBusy(true)
    try {
      const entry = await getEntry(key)
      if (entry) {
        onFlash(key, 'hit')
        setLastResult(`hit: ${key} = ${entry.value}`)
      } else {
        setLastResult(`miss: ${key} not found`)
      }
    } catch (err) {
      setLastResult(`error: ${err.message}`)
    } finally {
      setBusy(false)
    }
  }

  async function handleDelete() {
    if (!key) return
    setBusy(true)
    try {
      const removed = await deleteEntry(key)
      setLastResult(removed ? `deleted ${key}` : `${key} was not present`)
      onEntriesChanged()
    } catch (err) {
      setLastResult(`error: ${err.message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel" style={{ padding: 20 }}>
      <h3 className="panel-title" style={{ margin: 0 }}>
        Live Cache Tester
      </h3>
      <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
        <input placeholder="key" value={key} onChange={(e) => setKey(e.target.value)} style={fieldStyle} />
        <input placeholder="value" value={value} onChange={(e) => setValue(e.target.value)} style={fieldStyle} />
        <input
          placeholder="ttl (sec, optional)"
          type="number"
          value={ttl}
          onChange={(e) => setTtl(e.target.value)}
          style={{ ...fieldStyle, width: 140 }}
        />
        <button onClick={handlePut} disabled={busy || !key} style={actionButtonStyle}>
          PUT
        </button>
        <button onClick={handleGet} disabled={busy || !key} style={actionButtonStyle}>
          GET
        </button>
        <button onClick={handleDelete} disabled={busy || !key} style={{ ...actionButtonStyle, borderColor: 'var(--eviction-coral)' }}>
          DELETE
        </button>
      </div>
      {lastResult && (
        <p className="mono" style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 10 }}>
          → {lastResult}
        </p>
      )}
    </div>
  )
}

const fieldStyle = {
  background: 'var(--surface-raised)',
  color: 'var(--text)',
  border: '1px solid var(--border)',
  borderRadius: 4,
  padding: '6px 10px',
  fontSize: 13,
  flex: '1 1 120px'
}

const actionButtonStyle = {
  background: 'transparent',
  color: 'var(--text)',
  border: '1px solid var(--border)',
  borderRadius: 4,
  padding: '6px 14px',
  fontSize: 12,
  fontFamily: 'JetBrains Mono, monospace'
}
