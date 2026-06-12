import Chat from './components/Chat'

const AGENT_URL = import.meta.env.VITE_AGENT_URL ?? 'http://localhost:8000'

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
      <Chat agentUrl={AGENT_URL} />
    </div>
  )
}
