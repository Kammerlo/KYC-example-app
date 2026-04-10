import { useState } from 'react'
import { type ConnectedWallet } from './wallet'
import WalletConnect from './components/WalletConnect'
import IssuerDashboard from './components/IssuerDashboard'
import EntityView from './components/EntityView'
import UserFlow from './components/UserFlow'

export type Role = 'issuer' | 'entity' | 'user'

type Phase =
  | { tag: 'wallet-connect' }
  | { tag: 'app'; wallet: ConnectedWallet; role: Role; pkh: string; teRole?: number }

function roleLabel(role: Role) {
  return role === 'issuer' ? 'Issuer' : role === 'entity' ? 'Trusted Entity' : 'User'
}

export default function App() {
  const [phase, setPhase] = useState<Phase>({ tag: 'wallet-connect' })

  function handleConnected(wallet: ConnectedWallet, role: Role, pkh: string, teRole?: number) {
    setPhase({ tag: 'app', wallet, role, pkh, teRole })
  }

  function handleDisconnect() {
    setPhase({ tag: 'wallet-connect' })
  }

  if (phase.tag === 'wallet-connect') {
    return <WalletConnect onConnected={handleConnected} />
  }

  const { wallet, role } = phase

  return (
    <div>
      <div className="app-header">
        <h1>KERI Connect</h1>
        <div className="wallet-pill">
          {wallet.icon && <img src={wallet.icon} className="wallet-icon-sm" alt="" />}
          <span className="wallet-name">{wallet.name}</span>
          <span className={`role-badge role-${role}`}>{roleLabel(role)}</span>
          <button className="btn-icon disconnect-btn" onClick={handleDisconnect}>
            Disconnect
          </button>
        </div>
      </div>

      <div id="view">
        {role === 'issuer' && (
          <IssuerDashboard wallet={wallet} walletPkh={phase.pkh} />
        )}
        {role === 'entity' && <EntityView />}
        {role === 'user' && <UserFlow wallet={wallet} />}
      </div>
    </div>
  )
}
