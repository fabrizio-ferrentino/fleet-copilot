export interface TraceEntry {
  tool: string
  args: Record<string, unknown>
  resultSummary: string
}

export interface AskResponse {
  answer: string
  toolTrace: TraceEntry[]
}

export interface Message {
  role: 'user' | 'agent'
  text: string
  trace?: TraceEntry[]
  isError?: boolean
}
