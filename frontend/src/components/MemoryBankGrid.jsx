import { useEffect, useState } from 'react'

/*
  Signature element for this dashboard. Real caches (this one included)
  don't have fixed physical "slots" -- entries live in a hash map, not an
  array -- but rendering capacity as a literal grid of memory cells makes
  an otherwise-abstract concept (an eviction policy deciding what stays and
  what goes) directly visible: cells fill up, flash on access, and eject
  when the policy picks them as a victim.

  Cell -> key assignment is by sorted key order, recomputed on every
  entries update. That means a cell's position can shift as keys are
  added/removed -- an intentional simplification (see MemoryBankGrid docs
  in README) rather than trying to fake stable physical addressing for a
  structure that was never physically addressed to begin with.
*/
export default function MemoryBankGrid({ capacity, entries, flashes, onFlashComplete }) {
  const sortedKeys = Object.keys(entries).sort()
  const emptySlotCount = Math.max(0, capacity - sortedKeys.length)

  // Evicted keys are gone from `entries` immediately, but we still want to
  // show their cell fading out -- render them as extra "ghost" cells for
  // the duration of their flash.
  const evictingGhosts = Object.entries(flashes)
    .filter(([key, flash]) => flash.type === 'eviction' && !entries[key])
    .map(([key]) => key)

  return (
    <div className="panel" style={{ padding: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <h3 className="panel-title" style={{ margin: 0 }}>
          Memory Bank — {sortedKeys.length}/{capacity} slots occupied
        </h3>
        <Legend />
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(64px, 1fr))',
          gap: 6,
          marginTop: 14
        }}
      >
        {sortedKeys.map((key) => (
          <Cell key={key} label={key} flash={flashes[key]} onFlashComplete={() => onFlashComplete(key)} />
        ))}
        {evictingGhosts.map((key) => (
          <Cell key={key} label={key} flash={flashes[key]} onFlashComplete={() => onFlashComplete(key)} ghost />
        ))}
        {Array.from({ length: emptySlotCount }).map((_, i) => (
          <EmptyCell key={`empty-${i}`} />
        ))}
      </div>
    </div>
  )
}

function Legend() {
  const item = (color, label) => (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--text-muted)' }}>
      <span style={{ width: 8, height: 8, borderRadius: 2, background: color }} />
      {label}
    </span>
  )
  return (
    <div style={{ display: 'flex', gap: 12 }}>
      {item('var(--hit-teal)', 'hit')}
      {item('var(--miss-amber)', 'miss/insert')}
      {item('var(--eviction-coral)', 'evicted')}
    </div>
  )
}

function Cell({ label, flash, onFlashComplete, ghost = false }) {
  const [visible, setVisible] = useState(true)

  useEffect(() => {
    if (!flash) return
    const duration = flash.type === 'eviction' ? 550 : 700
    const timer = setTimeout(() => {
      setVisible(false)
      onFlashComplete()
    }, duration)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flash?.id])

  const color =
    flash?.type === 'hit' ? 'var(--hit-teal)' : flash?.type === 'miss' ? 'var(--miss-amber)' : flash?.type === 'eviction' ? 'var(--eviction-coral)' : 'var(--border)'

  if (ghost && !visible) return null

  return (
    <div
      className="mono"
      title={label}
      style={{
        aspectRatio: '1.4',
        borderRadius: 4,
        border: `1px solid ${color}`,
        background: flash ? `color-mix(in srgb, ${color} 16%, var(--surface-raised))` : 'var(--surface-raised)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: 10,
        color: flash ? color : 'var(--text-muted)',
        opacity: ghost && flash?.type === 'eviction' && !visible ? 0 : 1,
        transform: ghost && flash?.type === 'eviction' ? 'scale(0.85)' : 'scale(1)',
        transition: 'opacity 0.5s ease, transform 0.5s ease, background 0.2s ease, color 0.2s ease',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
        padding: '0 4px'
      }}
    >
      {label}
    </div>
  )
}

function EmptyCell() {
  return (
    <div
      style={{
        aspectRatio: '1.4',
        borderRadius: 4,
        border: '1px dashed var(--border)',
        background: 'transparent'
      }}
    />
  )
}
