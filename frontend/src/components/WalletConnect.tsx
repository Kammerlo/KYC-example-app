import { useState } from 'react'
import { type ConnectedWallet, getAvailableWallets, connectWalletByKey } from '../wallet'
import { apiGet, apiPost } from '../api'
import type { Role } from '../App'

interface Props {
  onConnected: (wallet: ConnectedWallet, role: Role, pkh: string) => void
}

type Status = { type: 'idle' } | { type: 'info'; msg: string } | { type: 'err'; msg: string }

function getSessionId(): string {
  let id = sessionStorage.getItem('keri-session-id')
  if (!id) {
    id = crypto.randomUUID()
    sessionStorage.setItem('keri-session-id', id)
  }
  return id
}

/** Extract 32-byte Ed25519 vkey hex from a COSE_Key hex string (map key -2, marker 215820). */
function extractVkeyFromCoseKey(coseKeyHex: string): string | null {
  const hex = coseKeyHex.toLowerCase()
  const marker = '215820'
  const idx = hex.indexOf(marker)
  if (idx === -1) return null
  const vkey = hex.slice(idx + marker.length, idx + marker.length + 64)
  return vkey.length === 64 ? vkey : null
}

export default function WalletConnect({ onConnected }: Props) {
  const wallets = getAvailableWallets()
  const [status, setStatus] = useState<Status>({ type: 'idle' })
  const [connecting, setConnecting] = useState(false)

  async function connect(walletKey: string) {
    setConnecting(true)
    setStatus({ type: 'info', msg: 'Connecting…' })
    try {
      const wallet = await connectWalletByKey(walletKey)
      const sessionId = getSessionId()

      // Fetch challenge
      setStatus({ type: 'info', msg: 'Preparing sign challenge…' })
      const chalRes = await apiGet(`/api/auth/challenge?sessionId=${encodeURIComponent(sessionId)}`)
      if (!chalRes.ok) throw new Error(`Challenge request failed: HTTP ${chalRes.status}`)
      const { challenge } = await chalRes.json() as { challenge: string }

      // Single sign — proves ownership AND gives us the vkey
      setStatus({ type: 'info', msg: 'Please approve the signature request in your wallet…' })
      const { signature, key } = await wallet.api.signData(wallet.changeAddress, challenge)

      // Extract and log vkey from COSE_Key
      const vkey = extractVkeyFromCoseKey(key)
      console.log('%c[KERI] Wallet setup info', 'font-weight:bold;color:#4a90d9')
      if (vkey) console.log('  cardano.issuer.vkey: ', vkey)

      // Verify signature on server → returns pkh
      setStatus({ type: 'info', msg: 'Verifying signature…' })
      const verRes = await apiPost('/api/auth/verify', { sessionId, address: wallet.changeAddress, signature, key })
      const verData = await verRes.json()
      if (!verRes.ok) throw new Error(verData.error ?? `HTTP ${verRes.status}`)
      const pkh: string = verData.pkh
      console.log('  cardano.issuer.pkh:  ', pkh)

      // Detect role
      setStatus({ type: 'info', msg: 'Detecting role…' })
      const roleRes = await apiPost('/api/auth/role', { address: wallet.changeAddress })
      if (!roleRes.ok) {
        const d = await roleRes.json()
        throw new Error(d.error ?? `HTTP ${roleRes.status}`)
      }
      const { role } = await roleRes.json()

      onConnected(wallet, role as Role, pkh)
    } catch (e) {
      setStatus({ type: 'err', msg: `Connection failed: ${e instanceof Error ? e.message : String(e)}` })
      setConnecting(false)
    }
  }

  return (
    <div>
      <h1>KERI Connect</h1>
      <div className="card">
        <p className="card-title">Connect your wallet</p>
        <p className="card-subtitle">Connect a Cardano wallet to access the application.</p>

        <div id="wallet-list">
          {wallets.length === 0 ? (
            <p className="status info">
              No Cardano wallet extension detected.<br />
              Install a CIP-30 wallet (e.g. Eternl, Lace, Nami) and reload.
            </p>
          ) : (
            wallets.map(w => (
              <button
                key={w.key}
                className="wallet-btn"
                disabled={connecting}
                onClick={() => connect(w.key)}
              >
                {w.icon && <img src={w.icon} className="wallet-icon" alt="" />}
                <span>{w.name}</span>
              </button>
            ))
          )}
        </div>

        {status.type !== 'idle' && (
          <p className={`status ${status.type}`}>{status.msg}</p>
        )}
      </div>
    </div>
  )
}