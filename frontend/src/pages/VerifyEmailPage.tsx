import { useEffect, useRef, useState } from 'react'

type VerificationStatus = 'loading' | 'success' | 'error'

type CsrfResponse = {
  token: string
  headerName: string
  parameterName: string
}

export default function VerifyEmailPage() {
  const [status, setStatus] = useState<VerificationStatus>('loading')
  const started = useRef(false)

  useEffect(() => {
    if (started.current) {
      return
    }

    started.current = true

    async function verifyEmail() {
      const token = new URLSearchParams(window.location.search).get('token')

      // Убираем секретный токен из адресной строки.
      window.history.replaceState({}, '', '/verify-email')

      if (!token) {
        setStatus('error')
        return
      }

      try {
        const csrfResponse = await fetch('/api/auth/csrf', {
          credentials: 'same-origin',
        })

        if (!csrfResponse.ok) {
          throw new Error('Failed to obtain CSRF token')
        }

        const csrf: CsrfResponse = await csrfResponse.json()

        const response = await fetch('/api/auth/verify-email', {
          method: 'POST',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/json',
            [csrf.headerName]: csrf.token,
          },
          body: JSON.stringify({ token }),
        })

        if (!response.ok) {
          throw new Error('Email verification failed')
        }

        setStatus('success')
      } catch {
        setStatus('error')
      }
    }

    verifyEmail()
  }, [])

  return (
    <main>
      <h1>Подтверждение email</h1>

      {status === 'loading' && <p>Проверяем ссылку...</p>}

      {status === 'success' && (
        <p>Почта успешно подтверждена! Теперь можно войти.</p>
      )}

      {status === 'error' && (
        <p role="alert">
          Не удалось подтвердить почту. Ссылка недействительна
          или срок её действия истёк.
        </p>
      )}
    </main>
  )
}