// Simple session helper: ensures a session id in sessionStorage
export function ensureSessionId(): string {
  const key = 'keri:sessionId'
  let id = sessionStorage.getItem(key)
  if (!id) {
    id = crypto.randomUUID()
    sessionStorage.setItem(key, id)
  }
  return id
}

export function getSessionId(): string | null {
  return sessionStorage.getItem('keri:sessionId')
}

