// API helper – proxied through Vite dev server to localhost:9000
export async function apiGet(path: string, opts: RequestInit = {}) {
  return fetch(path, { method: 'GET', ...opts })
}

export async function apiPost(path: string, body: unknown, opts: RequestInit = {}) {
  const { headers: extraHeaders, ...rest } = opts
  return fetch(path, {
    method: 'POST',
    body: JSON.stringify(body),
    headers: { 'Content-Type': 'application/json', ...(extraHeaders as Record<string, string> ?? {}) },
    ...rest,
  })
}