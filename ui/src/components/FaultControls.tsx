import { useState } from 'react'

// The four faults the simulator understands (see simulator/faults.py).
const FAULTS = [
  { key: 'gps_drift', label: 'GPS drift' },
  { key: 'error_burst', label: 'Error burst' },
  { key: 'battery_drain', label: 'Battery drain' },
  { key: 'silent', label: 'Silence' },
] as const

interface ActiveFault {
  id: number
  deviceId: string
  fault: string
  label: string
}

async function postFault(platformUrl: string, body: { deviceId?: string; fault: string }) {
  const res = await fetch(`${platformUrl}/api/control/fault`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`platform returned ${res.status}`)
  return (await res.json()) as { deviceId: string; fault: string }
}

/** Demo control panel: one click injects a fault on a random online device; chips clear it. */
export default function FaultControls({ platformUrl }: { platformUrl: string }) {
  const [active, setActive] = useState<ActiveFault[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function inject(fault: string, label: string) {
    setBusy(true)
    setError(null)
    try {
      const result = await postFault(platformUrl, { fault })
      setActive((prev) => [
        ...prev,
        { id: Date.now() + Math.random(), deviceId: result.deviceId, fault, label },
      ])
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  function clearOne(item: ActiveFault) {
    setActive((prev) => prev.filter((a) => a.id !== item.id))
    void postFault(platformUrl, { deviceId: item.deviceId, fault: 'clear' }).catch(() => {})
  }

  function recoverAll() {
    const items = active
    setActive([])
    for (const item of items) {
      void postFault(platformUrl, { deviceId: item.deviceId, fault: 'clear' }).catch(() => {})
    }
  }

  return (
    <div className="border-b border-slate-800 px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs font-medium text-slate-400">Inject anomaly:</span>
        {FAULTS.map((f) => (
          <button
            key={f.key}
            onClick={() => void inject(f.key, f.label)}
            disabled={busy}
            className="rounded-lg border border-slate-700 px-3 py-1 text-xs hover:border-sky-500 hover:text-slate-200 disabled:opacity-40"
          >
            {f.label}
          </button>
        ))}
        {active.length > 0 && (
          <button
            onClick={recoverAll}
            className="ml-auto rounded-lg border border-slate-700 px-3 py-1 text-xs text-emerald-300 hover:border-emerald-500 hover:text-emerald-200"
          >
            Recover all
          </button>
        )}
      </div>

      {active.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {active.map((a) => (
            <span
              key={a.id}
              className="flex items-center gap-1.5 rounded-full bg-slate-800 px-2.5 py-0.5 text-xs text-slate-300"
            >
              {a.label} · <span className="font-mono">{a.deviceId}</span>
              <button
                onClick={() => clearOne(a)}
                title="recover this device"
                className="text-slate-500 hover:text-red-400"
              >
                ✕
              </button>
            </span>
          ))}
        </div>
      )}

      {error && <p className="mt-2 text-xs text-red-400">Could not inject fault: {error}</p>}
    </div>
  )
}
