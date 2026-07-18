import { useRef, useState } from 'react'
import { runBenchmark, uploadTraceBenchmark } from '../api.js'

const AVAILABLE_POLICIES = [
  { id: 'LRU', label: 'LRU', enabled: true },
  { id: 'LFU', label: 'LFU', enabled: true },
  { id: 'ARC', label: 'ARC', enabled: true },
  { id: 'TinyLFU', label: 'TinyLFU', enabled: true }
]

export default function ConfigPanel({ onResults, onError }) {
  const [traceType, setTraceType] = useState('ZIPFIAN')
  const [length, setLength] = useState(100000)
  const [keyRange, setKeyRange] = useState(2000)
  const [zipfianExponent, setZipfianExponent] = useState(1.2)
  const [loopSize, setLoopSize] = useState(150)
  const [seed, setSeed] = useState(42)
  const [capacity, setCapacity] = useState(100)
  const [policies, setPolicies] = useState(['LRU', 'LFU'])
  const [running, setRunning] = useState(false)
  const fileInputRef = useRef(null)

  function togglePolicy(id) {
    setPolicies((prev) => (prev.includes(id) ? prev.filter((p) => p !== id) : [...prev, id]))
  }

  async function handleRun() {
    if (policies.length === 0) {
      onError('Select at least one policy to benchmark.')
      return
    }
    setRunning(true)
    onError(null)
    try {
      const results = await runBenchmark({
        traceType,
        length: Number(length),
        keyRange: Number(keyRange),
        zipfianExponent: traceType === 'ZIPFIAN' ? Number(zipfianExponent) : null,
        loopSize: traceType === 'LOOPING' ? Number(loopSize) : null,
        seed: Number(seed),
        capacity: Number(capacity),
        policies
      })
      onResults(results)
    } catch (err) {
      onError(err.message)
    } finally {
      setRunning(false)
    }
  }

  async function handleFileUpload(e) {
    const file = e.target.files?.[0]
    if (!file) return
    if (policies.length === 0) {
      onError('Select at least one policy before uploading a trace.')
      return
    }
    setRunning(true)
    onError(null)
    try {
      const results = await uploadTraceBenchmark(file, policies, Number(capacity))
      onResults(results)
    } catch (err) {
      onError(err.message)
    } finally {
      setRunning(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  return (
    <div className="panel" style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 16 }}>
      <h3 className="panel-title" style={{ margin: 0 }}>
        Benchmark Config
      </h3>

      <Field label="Trace type">
        <select value={traceType} onChange={(e) => setTraceType(e.target.value)} style={selectStyle}>
          <option value="SEQUENTIAL">Sequential</option>
          <option value="UNIFORM">Uniform random</option>
          <option value="ZIPFIAN">Zipfian (skewed)</option>
          <option value="LOOPING">Looping</option>
        </select>
      </Field>

      <Field label="Trace length">
        <input type="number" min={100} value={length} onChange={(e) => setLength(e.target.value)} style={inputStyle} />
      </Field>

      <Field label="Key range">
        <input type="number" min={10} value={keyRange} onChange={(e) => setKeyRange(e.target.value)} style={inputStyle} />
      </Field>

      {traceType === 'ZIPFIAN' && (
        <Field label="Zipfian exponent (skew)">
          <input
            type="number"
            step="0.1"
            min={0}
            value={zipfianExponent}
            onChange={(e) => setZipfianExponent(e.target.value)}
            style={inputStyle}
          />
        </Field>
      )}

      {traceType === 'LOOPING' && (
        <Field label="Loop size">
          <input type="number" min={1} value={loopSize} onChange={(e) => setLoopSize(e.target.value)} style={inputStyle} />
        </Field>
      )}

      <Field label="Random seed">
        <input type="number" value={seed} onChange={(e) => setSeed(e.target.value)} style={inputStyle} />
      </Field>

      <Field label="Cache capacity">
        <input type="number" min={1} value={capacity} onChange={(e) => setCapacity(e.target.value)} style={inputStyle} />
      </Field>

      <Field label="Policies to compare">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {AVAILABLE_POLICIES.map((p) => (
            <label
              key={p.id}
              className="mono"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                fontSize: 12,
                padding: '4px 8px',
                borderRadius: 4,
                border: '1px solid var(--border)',
                background: policies.includes(p.id) ? 'var(--surface-raised)' : 'transparent',
                color: p.enabled ? 'var(--text)' : 'var(--text-muted)',
                opacity: p.enabled ? 1 : 0.5,
                cursor: p.enabled ? 'pointer' : 'not-allowed'
              }}
            >
              <input
                type="checkbox"
                checked={policies.includes(p.id)}
                disabled={!p.enabled}
                onChange={() => togglePolicy(p.id)}
              />
              {p.label}
              {!p.enabled && ' (soon)'}
            </label>
          ))}
        </div>
      </Field>

      <button onClick={handleRun} disabled={running} style={runButtonStyle}>
        {running ? 'Running…' : '▶ Run Benchmark'}
      </button>

      <div>
        <div className="panel-title" style={{ marginBottom: 6 }}>
          or upload a trace
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept=".csv,.txt"
          onChange={handleFileUpload}
          disabled={running}
          style={{ fontSize: 12, color: 'var(--text-muted)', width: '100%' }}
        />
        <p style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 4 }}>One key per line, no header.</p>
      </div>
    </div>
  )
}

function Field({ label, children }) {
  return (
    <div>
      <label className="mono" style={{ display: 'block', fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>
        {label}
      </label>
      {children}
    </div>
  )
}

const inputStyle = {
  width: '100%',
  background: 'var(--surface-raised)',
  color: 'var(--text)',
  border: '1px solid var(--border)',
  borderRadius: 4,
  padding: '6px 8px',
  fontSize: 13
}

const selectStyle = { ...inputStyle }

const runButtonStyle = {
  background: 'var(--accent-amber)',
  color: '#1a1206',
  border: 'none',
  borderRadius: 4,
  padding: '10px 16px',
  fontWeight: 600,
  fontSize: 13,
  fontFamily: 'JetBrains Mono, monospace'
}
