import { useState, useEffect } from 'react'
import { type ConnectedWallet } from '../wallet'
import { apiGet, apiPost } from '../api'

interface Props {
  wallet: ConnectedWallet
  onVerified: () => void
  onCancel: () => void
}

type Status =
  | { type: 'idle' }
  | { type: 'info'; msg: string }
  | { type: 'ok'; msg: string }
  | { type: 'err'; msg: string }

function getSessionId(): string {
  let id = sessionStorage.getItem('keri-session-id')
  if (!id) {
    id = crypto.randomUUID()
    sessionStorage.setItem('keri-session-id', id)
  }
  return id
}

export default function AuthChallenge({ wallet, onVerified, onCancel }: Props) {
  const [status, setStatus] = useState<Status>({ type: 'info', msg: 'Preparing challenge…' })
  const [signing, setSigning] = useState(false)

  useEffect(() => {
    setStatus({ type: 'info', msg: 'Challenge ready. Please sign to prove wallet ownership.' })
  }, [])

  async function sign() {
    setSigning(true)
    setStatus({ type: 'info', msg: 'Requesting challenge from server…' })

    try {
      const sessionId = getSessionId()

      // 1. Get challenge from backend
      const chalRes = await apiGet(`/api/auth/challenge?sessionId=${encodeURIComponent(sessionId)}`)
      if (!chalRes.ok) throw new Error(`Challenge request failed: HTTP ${chalRes.status}`)
      const { challenge } = await chalRes.json() as { challenge: string }

      setStatus({ type: 'info', msg: 'Please approve the signature request in your wallet…' })

      // 2. Sign the challenge with the wallet (CIP-30 signData)
      const { signature, key } = await wallet.api.signData(wallet.changeAddress, challenge)

      setStatus({ type: 'info', msg: 'Verifying signature…' })

      // 3. Send to backend for verification
      const verRes = await apiPost('/api/auth/verify', {
        sessionId,
        address: wallet.changeAddress,
        signature,
        key,
      })
      const verData = await verRes.json()
      if (!verRes.ok) throw new Error(verData.error ?? `HTTP ${verRes.status}`)

      setStatus({ type: 'ok', msg: 'Identity verified successfully!' })
      setTimeout(onVerified, 800)
    } catch (e) {
      setStatus({ type: 'err', msg: `Verification failed: ${e instanceof Error ? e.message : String(e)}` })
      setSigning(false)
    }
  }

  return (
    <div>
      <h1>KERI Connect</h1>
      <div className="card">
        <div className="auth-challenge-header">
          <div className="auth-shield-icon">🔐</div>
          <p className="card-title" style={{ margin: 0 }}>Prove wallet ownership</p>
        </div>
        <p className="card-subtitle" style={{ marginTop: '.75rem' }}>
          Sign a one-time challenge to prove you control this wallet. The challenge is tied
          to your browser session and cannot be replayed.
        </p>

        <div className="wallet-info-row">
          {wallet.icon && <img src={wallet.icon} className="wallet-icon" alt="" />}
          <div>
            <div className="wallet-name">{wallet.name}</div>
            <div className="wallet-addr">{wallet.changeAddress.slice(0, 28)}…</div>
          </div>
        </div>

        {status.type !== 'idle' && (
          <p className={`status ${status.type}`}>{status.msg}</p>
        )}

        <div className="auth-actions">
          <button className="btn-primary" onClick={sign} disabled={signing}>
            {signing ? 'Waiting for wallet…' : 'Sign Challenge'}
          </button>
          <button className="btn-ghost" onClick={onCancel} disabled={signing}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  )
}