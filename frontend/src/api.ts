// Small API helper - uses VITE_API_BASE if set, otherwise defaults to http://<host>:9000
export const API_BASE = (import.meta.env && (import.meta.env.VITE_API_BASE as string)) || `${location.protocol}//${location.hostname}:9000`

function buildUrl(path: string) {
  if (/^https?:\/\//.test(path)) return path
  // ensure leading slash
  const p = path.startsWith('/') ? path : `/${path}`
  return `${API_BASE}${p}`
}

export async function apiGet(path: string, opts: RequestInit = {}) {
  const url = buildUrl(path)
  const res = await fetch(url, { method: 'GET', ...opts })
  return res
}

export async function apiPost(path: string, body: unknown, opts: RequestInit = {}) {
  const url = buildUrl(path)
  const res = await fetch(url, { method: 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) }, ...opts })
  return res
}
