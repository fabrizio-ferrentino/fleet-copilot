export interface TraceEntry {
  tool: string
  args: Record<string, unknown>
  resultSummary: string
}

export interface AskResponse {
  answer: string
  toolTrace: TraceEntry[]
}

export interface Device {
  id: string
  online: boolean
  firstSeen: string
  lastSeen: string
  status: 'OK' | 'WARNING' | 'ERROR'
  batteryPct: number | null
  lat: number | null
  lon: number | null
  firmware: string | null
}

export interface Message {
  role: 'user' | 'agent'
  text: string
  trace?: TraceEntry[]
  isError?: boolean
}
