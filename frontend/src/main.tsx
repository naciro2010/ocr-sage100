import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

// Service Worker : cache offline-first des assets et des GET API.
// On l'enregistre apres le premier render pour ne pas concurrencer la
// peinture initiale. Pas de SW en mode dev (HMR + SW = pieges classiques).
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(err => {
      console.warn('[SW] enregistrement echoue:', err)
    })
  })
}
