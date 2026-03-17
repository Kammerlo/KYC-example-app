// Wallet helper: CIP-30 window.cardano connect

export type WalletInfo = {
  name: string
  api?: unknown
}

export async function connectWallet(): Promise<WalletInfo> {
  // CIP-30 (window.cardano)
  // @ts-ignore
  const cardano = window.cardano
  if (cardano) {
    const providers = Object.keys(cardano).map(k => ({ key: k, api: (cardano as Record<string, any>)[k] }))
    for (const p of providers) {
      try {
        if (p.api && typeof p.api.enable === 'function') {
          const api = await p.api.enable()
          return { name: p.key, api }
        }
      } catch (_) {
        // user rejected or provider not ready
      }
    }
  }
  throw new Error('No Cardano wallet detected or user rejected connection')
}
