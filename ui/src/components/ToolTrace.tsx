import type { TraceEntry } from '../types'

/** Collapsible "how I investigated" panel: the agent's tool calls, in order. */
export default function ToolTrace({ trace, open }: { trace: TraceEntry[]; open?: boolean }) {
  if (trace.length === 0) return null
  return (
    <details open={open} className="mt-3 rounded-lg border border-slate-700 bg-slate-900/60">
      <summary className="cursor-pointer select-none px-3 py-2 text-xs font-medium text-slate-400 hover:text-slate-200">
        how I investigated ({trace.length} tool call{trace.length === 1 ? '' : 's'})
      </summary>
      <ol className="space-y-1 px-3 pb-3 font-mono text-xs text-slate-400">
        {trace.map((entry, i) => (
          <li key={i} className="break-all">
            <span className="text-slate-500">{i + 1}.</span>{' '}
            <span className="text-sky-400">{entry.tool}</span>{' '}
            <span>{JSON.stringify(entry.args)}</span>{' '}
            <span className="text-slate-500">→ {entry.resultSummary}</span>
          </li>
        ))}
      </ol>
    </details>
  )
}
