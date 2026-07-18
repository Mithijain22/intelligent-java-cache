import { useCallback, useEffect, useRef, useState } from 'react'
import Header from './components/Header.jsx'
import ConfigPanel from './components/ConfigPanel.jsx'
import MemoryBankGrid from './components/MemoryBankGrid.jsx'
import StatsCards from './components/StatsCards.jsx'
import ComparisonChart from './components/ComparisonChart.jsx'
import LiveCacheTester from './components/LiveCacheTester.jsx'
import { useLiveStats } from './useLiveStats.js'
import { getAllEntries } from './api.js'

const ENTRIES_POLL_INTERVAL_MS = 2000
// Memory Bank Grid capacity display: mirrors application.yml's cache.capacity.
// There's no REST endpoint for "configured capacity" specifically (only live
// size), so this is set to match the default in cache-api/src/main/resources/application.yml.
// Change this if you change that config.
const DISPLAY_CAPACITY = 100

export default function App() {
  const { connected, latestStats, evictionEvents } = useLiveStats()
  const [entries, setEntries] = useState({})
  const [flashes, setFlashes] = useState({})
  const [benchmarkResults, setBenchmarkResults] = useState(null)
  const [comparisonMetric, setComparisonMetric] = useState('hitRate')
  const [error, setError] = useState(null)
  const seenEvictionIds = useRef(new Set())

  const refreshEntries = useCallback(async () => {
    try {
      const data = await getAllEntries()
      setEntries(data || {})
    } catch {
      // Live cache endpoint unreachable -- leave last-known entries in place
      // rather than clearing the grid, so a transient network blip doesn't
      // flash every occupied cell to empty.
    }
  }, [])

  useEffect(() => {
    refreshEntries()
    const interval = setInterval(refreshEntries, ENTRIES_POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [refreshEntries])

  // Real eviction events arrive over the WebSocket (see useLiveStats) --
  // turn each new one into a grid flash, and refresh entries since capacity
  // just changed.
  useEffect(() => {
    const latest = evictionEvents[evictionEvents.length - 1]
    if (!latest) return
    const id = `${latest.key}-${evictionEvents.length}`
    if (seenEvictionIds.current.has(id)) return
    seenEvictionIds.current.add(id)
    flash(latest.key, 'eviction')
    refreshEntries()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [evictionEvents])

  function flash(key, type) {
    setFlashes((prev) => ({ ...prev, [key]: { type, id: `${key}-${Date.now()}-${Math.random()}` } }))
  }

  function clearFlash(key) {
    setFlashes((prev) => {
      const next = { ...prev }
      delete next[key]
      return next
    })
  }

  return (
    <div>
      <Header connected={connected} policy={latestStats?.policy} />
      <div className="app-shell">
        <aside style={{ padding: 20, borderRight: '1px solid var(--border)' }}>
          <ConfigPanel onResults={setBenchmarkResults} onError={setError} />
        </aside>

        <main style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 20 }}>
          {error && (
            <div
              className="mono"
              style={{
                background: 'color-mix(in srgb, var(--eviction-coral) 12%, var(--surface))',
                border: '1px solid var(--eviction-coral)',
                color: 'var(--eviction-coral)',
                borderRadius: 4,
                padding: '10px 14px',
                fontSize: 12
              }}
            >
              {error}
            </div>
          )}

          <MemoryBankGrid capacity={DISPLAY_CAPACITY} entries={entries} flashes={flashes} onFlashComplete={clearFlash} />

          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr)', gap: 20 }}>
            <StatsCards stats={latestStats} />
          </div>

          <ComparisonChart results={benchmarkResults} metric={comparisonMetric} onMetricChange={setComparisonMetric} />

          <LiveCacheTester onFlash={flash} onEntriesChanged={refreshEntries} />
        </main>
      </div>
    </div>
  )
}
