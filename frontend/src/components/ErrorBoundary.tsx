import { Component } from 'react'
import type { ReactNode, ErrorInfo } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'

interface Props { children: ReactNode }
interface State { error: Error | null }

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error) { return { error } }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('React error boundary:', error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: 48, textAlign: 'center' }}>
          <AlertTriangle size={40} style={{ color: 'var(--danger)', marginBottom: 16 }} />
          <h2 style={{ fontSize: 18, fontWeight: 700, marginBottom: 8 }}>Une erreur est survenue</h2>
          <p style={{ fontSize: 13, color: 'var(--ink-40)', marginBottom: 16 }}>{this.state.error.message}</p>
          <button className="btn btn-primary" onClick={() => { this.setState({ error: null }); window.location.reload() }}>
            <RefreshCw size={14} /> Recharger
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
