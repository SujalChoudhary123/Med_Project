import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './utils/authFetch.js'
import './index.css'
import App from './App.jsx'
// Must come after every component, so the phone rules win over theirs.
import './mobile.css'

// Lets the app open without internet and be added to a phone's home screen.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {})
  })
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
