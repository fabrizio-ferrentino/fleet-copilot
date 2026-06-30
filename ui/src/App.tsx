import Chat from './components/Chat'
import FaultControls from './components/FaultControls'
import FleetMap from './components/FleetMap'

const AGENT_URL = import.meta.env.VITE_AGENT_URL ?? 'http://localhost:8000'
const PLATFORM_URL = import.meta.env.VITE_PLATFORM_URL ?? 'http://localhost:8080'

export default function App() {
  return (
    <div className="flex h-screen flex-col bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 px-6 py-4">
        <h1 className="text-lg font-semibold">
          Fleet Copilot
          <span className="ml-3 text-sm font-normal text-slate-400">
            ask anything about your device fleet
          </span>
        </h1>
      </header>
      <div className="flex min-h-0 flex-1">
        <div className="flex w-1/2 flex-col border-r border-slate-800">
          <FaultControls platformUrl={PLATFORM_URL} />
          <div className="min-h-0 flex-1">
            <FleetMap platformUrl={PLATFORM_URL} />
          </div>
        </div>
        <div className="flex w-1/2 flex-col">
          <Chat agentUrl={AGENT_URL} />
        </div>
      </div>
    </div>
  )
}
