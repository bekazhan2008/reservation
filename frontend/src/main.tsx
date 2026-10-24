import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import VerifyEmailPage from './pages/VerifyEmailPage'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {window.location.pathname === '/verify-email'
      ? <VerifyEmailPage />
      : <App />}
  </StrictMode>,
)
