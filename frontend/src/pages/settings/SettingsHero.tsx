import type React from 'react'

type HeroStatus = 'active' | 'idle' | 'off'

export default function SettingsHero({
  eyebrow, title, icon, lead, status, statusLabel, kpi, kpiLabel,
}: {
  eyebrow: string
  title: React.ReactNode
  icon?: React.ReactNode
  lead: string
  status: HeroStatus
  statusLabel: string
  kpi: string
  kpiLabel: string
}) {
  const dotTone = status === 'active' ? 'tone-active' : status === 'idle' ? 'tone-fallback' : ''
  return (
    <div className="settings-hero">
      <div style={{ position: 'relative', zIndex: 1 }}>
        <div className="settings-hero-eyebrow">{eyebrow}</div>
        <h2 className="settings-hero-title">
          {icon}
          <span>{title}</span>
        </h2>
        <p className="settings-hero-lead">{lead}</p>
        <div className={`status-banner ${dotTone}`} style={{
          marginTop: 16, marginBottom: 0, gridTemplateColumns: 'auto 1fr',
          background: 'transparent', border: 'none', padding: '0',
        }}>
          <span className="status-banner-dot" aria-hidden="true" />
          <div className="status-banner-title" style={{ fontSize: 12, fontWeight: 600 }}>
            {statusLabel}
          </div>
        </div>
      </div>
      <div className="settings-hero-aside">
        <span>{kpiLabel.toUpperCase()}</span>
        <strong>{kpi}</strong>
      </div>
    </div>
  )
}
