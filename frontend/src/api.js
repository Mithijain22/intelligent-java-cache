// Thin fetch wrappers around the cache-api REST surface. Relative paths
// (e.g. '/cache/stats') work as-is in dev via the Vite proxy (vite.config.js)
// and in a typical production setup where the built frontend is served
// behind the same reverse proxy as the API. Set VITE_API_BASE_URL at build
// time if you're hosting the frontend and API on different origins.
const BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

async function handleResponse(res) {
  if (res.status === 404) {
    return null
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`Request failed: ${res.status} ${text}`)
  }
  const contentType = res.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    return res.json()
  }
  return null
}

export async function getStats() {
  const res = await fetch(`${BASE_URL}/cache/stats`)
  return handleResponse(res)
}

export async function getAllEntries() {
  const res = await fetch(`${BASE_URL}/cache`)
  return handleResponse(res)
}

export async function getEntry(key) {
  const res = await fetch(`${BASE_URL}/cache/${encodeURIComponent(key)}`)
  return handleResponse(res)
}

export async function putEntry(key, value, ttlSeconds) {
  const res = await fetch(`${BASE_URL}/cache/${encodeURIComponent(key)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ value, ttlSeconds: ttlSeconds ?? null })
  })
  return handleResponse(res)
}

export async function deleteEntry(key) {
  const res = await fetch(`${BASE_URL}/cache/${encodeURIComponent(key)}`, {
    method: 'DELETE'
  })
  if (res.status === 404) return false
  if (!res.ok) throw new Error(`Delete failed: ${res.status}`)
  return true
}

// traceConfig: { traceType, length, keyRange, zipfianExponent, loopSize, seed, capacity, policies }
export async function runBenchmark(traceConfig) {
  const res = await fetch(`${BASE_URL}/benchmark/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(traceConfig)
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`Benchmark run failed: ${res.status} ${text}`)
  }
  return res.json()
}

export async function uploadTraceBenchmark(file, policies, capacity) {
  const form = new FormData()
  form.append('file', file)
  form.append('policies', policies.join(','))
  form.append('capacity', String(capacity))

  const res = await fetch(`${BASE_URL}/benchmark/upload`, {
    method: 'POST',
    body: form
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`Upload benchmark failed: ${res.status} ${text}`)
  }
  return res.json()
}

export function websocketUrl() {
  if (BASE_URL) {
    return BASE_URL.replace(/^http/, 'ws') + '/ws/stats'
  }
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  // In dev, Vite's proxy forwards /ws to the backend (see vite.config.js).
  return `${protocol}://${window.location.host}/ws/stats`
}
