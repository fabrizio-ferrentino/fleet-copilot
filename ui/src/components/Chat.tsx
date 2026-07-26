import { useEffect, useRef, useState } from 'react'
import type { Message } from '../types'
import ToolTrace from './ToolTrace'

const SUGGESTIONS = [
  'Which devices are offline right now?',
  'Anything anomalous in the last hour?',
  'Why did dev-042 stop transmitting?',
]

export default function Chat({ agentUrl }: { agentUrl: string }) {
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  async function send(question: string) {
    const trimmed = question.trim()
    if (!trimmed || loading) return
    setMessages((prev) => [
      ...prev,
      { role: 'user', text: trimmed },
      { role: 'agent', text: '', trace: [], streaming: true },
    ])
    setInput('')
    setLoading(true)

    // Input is disabled while loading, so the agent placeholder stays last throughout.
    const updateAgent = (updater: (m: Message) => Message) =>
      setMessages((prev) => prev.map((m, i) => (i === prev.length - 1 ? updater(m) : m)))

    const handleFrame = (frame: string) => {
      let event = 'message'
      let data = ''
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trim()
      }
      if (!data) return
      const payload = JSON.parse(data)
      if (event === 'tool') {
        updateAgent((m) => ({
          ...m,
          trace: [
            ...(m.trace ?? []),
            { tool: payload.tool, args: payload.args, resultSummary: payload.resultSummary },
          ],
        }))
      } else if (event === 'answer') {
        updateAgent((m) => ({ ...m, text: payload.text }))
      } else if (event === 'error') {
        updateAgent((m) => ({ ...m, text: payload.message, isError: true }))
      }
    }

    try {
      const response = await fetch(`${agentUrl}/ask/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: trimmed }),
      })
      if (!response.ok || !response.body) throw new Error(`agent returned ${response.status}`)

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx = buffer.indexOf('\n\n')
        while (idx !== -1) {
          handleFrame(buffer.slice(0, idx))
          buffer = buffer.slice(idx + 2)
          idx = buffer.indexOf('\n\n')
        }
      }
      updateAgent((m) => ({ ...m, streaming: false }))
    } catch (error) {
      updateAgent((m) => ({
        ...m,
        text: `Could not reach the agent (${String(error)}). Is it running on ${agentUrl}?`,
        isError: true,
        streaming: false,
      }))
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col overflow-hidden px-4">
      <div className="flex-1 space-y-4 overflow-y-auto py-6">
        {messages.length === 0 && (
          <div className="mt-16 text-center text-slate-400">
            <p className="mb-4">Try one of these:</p>
            <div className="flex flex-col items-center gap-2">
              {SUGGESTIONS.map((s) => (
                <button
                  key={s}
                  onClick={() => void send(s)}
                  className="rounded-lg border border-slate-700 px-4 py-2 text-sm hover:border-sky-500 hover:text-slate-200"
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((message, i) => (
          <div key={i} className={message.role === 'user' ? 'flex justify-end' : 'flex'}>
            <div
              className={
                message.role === 'user'
                  ? 'max-w-[80%] rounded-2xl rounded-br-sm bg-sky-600 px-4 py-2'
                  : message.isError
                    ? 'max-w-[85%] rounded-2xl rounded-bl-sm border border-red-800 bg-red-950 px-4 py-3 text-red-200'
                    : 'max-w-[85%] rounded-2xl rounded-bl-sm bg-slate-800 px-4 py-3'
              }
            >
              {message.text ? (
                <p className="whitespace-pre-wrap text-sm leading-relaxed">{message.text}</p>
              ) : message.streaming ? (
                <p className="text-sm text-slate-400">
                  investigating<span className="animate-pulse">…</span>
                </p>
              ) : null}
              {message.trace && <ToolTrace trace={message.trace} open={message.streaming} />}
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      <form
        className="flex gap-2 border-t border-slate-800 py-4"
        onSubmit={(event) => {
          event.preventDefault()
          void send(input)
        }}
      >
        <input
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder="Ask about the fleet…"
          disabled={loading}
          className="flex-1 rounded-lg border border-slate-700 bg-slate-900 px-4 py-2 text-sm outline-none placeholder:text-slate-500 focus:border-sky-500 disabled:opacity-50"
        />
        <button
          type="submit"
          disabled={loading || !input.trim()}
          className="rounded-lg bg-sky-600 px-5 py-2 text-sm font-medium hover:bg-sky-500 disabled:opacity-40"
        >
          Send
        </button>
      </form>
    </main>
  )
}
