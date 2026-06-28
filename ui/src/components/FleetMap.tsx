import { useEffect, useState } from 'react'
import { CircleMarker, MapContainer, TileLayer } from 'react-leaflet'
import type { Device } from '../types'

// Fleet starting area (Campania, Italy) — matches the simulator's BASE_LAT / BASE_LON.
const CENTER: [number, number] = [40.78, 14.59]
const REFRESH_MS = 4000

// Status colours: offline (gray) takes priority over the last-reported status.
function colorFor(device: Device): string {
  if (!device.online) return '#64748b' // slate-500
  if (device.status === 'ERROR') return '#ef4444' // red-500
  if (device.status === 'WARNING') return '#f59e0b' // amber-500
  return '#22c55e' // green-500
}

type Located = Device & { lat: number; lon: number }

export default function FleetMap({ platformUrl }: { platformUrl: string }) {
  const [devices, setDevices] = useState<Device[]>([])

  useEffect(() => {
    let active = true
    async function load() {
      try {
        const res = await fetch(`${platformUrl}/api/devices`)
        if (!res.ok) return
        const data: Device[] = await res.json()
        if (active) setDevices(data)
      } catch {
        // platform unreachable; keep the last known markers on screen
      }
    }
    void load()
    const id = setInterval(load, REFRESH_MS)
    return () => {
      active = false
      clearInterval(id)
    }
  }, [platformUrl])

  const located = devices.filter((d): d is Located => d.lat != null && d.lon != null)

  return (
    <div className="relative h-full w-full">
      <MapContainer center={CENTER} zoom={9} scrollWheelZoom className="h-full w-full bg-slate-900">
        <TileLayer
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        />
        {located.map((d) => (
          <CircleMarker
            key={d.id}
            center={[d.lat, d.lon]}
            radius={5}
            pathOptions={{
              color: colorFor(d),
              fillColor: colorFor(d),
              fillOpacity: 0.85,
              weight: 1,
            }}
          />
        ))}
      </MapContainer>
      <div className="pointer-events-none absolute top-3 right-3 z-[1000] rounded-lg border border-slate-700 bg-slate-900/80 px-3 py-2 text-xs text-slate-200 shadow">
        <Legend color="#22c55e" label="online" />
        <Legend color="#f59e0b" label="warning" />
        <Legend color="#ef4444" label="error" />
        <Legend color="#64748b" label="offline" />
      </div>
    </div>
  )
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <div className="flex items-center gap-2 py-0.5">
      <span className="inline-block h-2.5 w-2.5 rounded-full" style={{ backgroundColor: color }} />
      {label}
    </div>
  )
}
