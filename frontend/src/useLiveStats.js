import { useEffect, useRef, useState } from 'react'
import { websocketUrl } from './api.js'

// Connects to the backend's live stats stream (see CacheEventBroadcaster on
// the server) and reconnects automatically with backoff if the connection
// drops -- e.g. if you restart the Spring Boot process while the dashboard
// stays open, it should just quietly recover instead of leaving the UI stuck
// on stale data.
export function useLiveStats() {
  const [connected, setConnected] = useState(false)
  const [latestStats, setLatestStats] = useState(null)
  const [evictionEvents, setEvictionEvents] = useState([])
  const socketRef = useRef(null)
  const reconnectDelayRef = useRef(1000)
  const closedByUsRef = useRef(false)

  useEffect(() => {
    closedByUsRef.current = false
    connect()
    return () => {
      closedByUsRef.current = true
      socketRef.current?.close()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function connect() {
    const socket = new WebSocket(websocketUrl())
    socketRef.current = socket

    socket.onopen = () => {
      setConnected(true)
      reconnectDelayRef.current = 1000 // reset backoff on successful connect
    }

    socket.onmessage = (event) => {
      let payload
      try {
        payload = JSON.parse(event.data)
      } catch {
        return
      }
      if (payload.type === 'stats') {
        setLatestStats(payload)
      } else if (payload.type === 'eviction') {
        setEvictionEvents((prev) => [...prev.slice(-49), payload]) // keep last 50
      }
    }

    socket.onclose = () => {
      setConnected(false)
      if (closedByUsRef.current) return
      const delay = reconnectDelayRef.current
      setTimeout(connect, delay)
      reconnectDelayRef.current = Math.min(delay * 2, 15000) // exponential backoff, capped at 15s
    }

    socket.onerror = () => {
      socket.close()
    }
  }

  return { connected, latestStats, evictionEvents }
}
