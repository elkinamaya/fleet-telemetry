import { useEffect, useRef, useState } from 'react'
import { getTelemetryStreamUrl } from '../api/client'
import { parseTelemetryEvent, type TelemetryEvent } from '../api/types'

export type StreamStatus = 'connecting' | 'connected' | 'reconnecting'

export function useTelemetryStream(
  onTelemetry: (event: TelemetryEvent) => void,
  onStatusChange: (status: StreamStatus) => void,
): StreamStatus {
  const [status, setStatus] = useState<StreamStatus>('connecting')
  const onTelemetryRef = useRef(onTelemetry)
  const onStatusChangeRef = useRef(onStatusChange)

  useEffect(() => {
    onTelemetryRef.current = onTelemetry
  }, [onTelemetry])

  useEffect(() => {
    onStatusChangeRef.current = onStatusChange
  }, [onStatusChange])

  useEffect(() => {
    let active = true
    const seenEventIds = new Set<string>()
    const source = new EventSource(getTelemetryStreamUrl())

    source.onopen = () => {
      if (active) {
        setStatus('connected')
        onStatusChangeRef.current('connected')
      }
    }
    source.onerror = () => {
      if (active) {
        setStatus('reconnecting')
        onStatusChangeRef.current('reconnecting')
      }
    }
    source.addEventListener('telemetry', (message: MessageEvent<string>) => {
      let decoded: unknown
      try {
        decoded = JSON.parse(message.data)
      } catch {
        if (active) console.warn('SSE telemetry event contained invalid JSON')
        return
      }
      const event = parseTelemetryEvent(decoded)
      if (!event) {
        if (active) console.warn('SSE telemetry event did not match the expected contract')
        return
      }
      if (seenEventIds.has(event.eventId)) return
      seenEventIds.add(event.eventId)
      if (seenEventIds.size > 1000) {
        const oldestEventId = seenEventIds.values().next().value
        if (oldestEventId) seenEventIds.delete(oldestEventId)
      }
      if (active) onTelemetryRef.current(event)
    })

    return () => {
      active = false
      source.close()
    }
  }, [])

  return status
}
