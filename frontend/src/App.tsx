import { useState } from 'react'
import { type ConnectedWallet } from './wallet'
import WalletConnect from './components/WalletConnect'
import IssuerDashboard from './components/IssuerDashboard'
import AllowListDashboard from './components/AllowListDashboard'
import UserFlow from './components/UserFlow'

export type Role = 'issuer' | 'entity' | 'user'

type Phase =
  | { tag: 'wallet-connect' }
  | { tag: 'app'; wallet: ConnectedWallet; role: Role; pkh: string; teRole?: number }

function roleLabel(role: Role) {
  return role === 'issuer' ? 'Issuer' : role === 'entity' ? 'Trusted Entity' : 'User'
}

type IssuerTab = 'tel' | 'allowlist'

export default function App() {
  const [phase, setPhase] = useState<Phase>({ tag: 'wallet-connect' })
  const [issuerTab, setIssuerTab] = useState<IssuerTab>('tel')

  function handleConnected(wallet: ConnectedWallet, role: Role, pkh: string, teRole?: number) {
    setPhase({ tag: 'app', wallet, role, pkh, teRole })
  }

  function handleDisconnect() {
    setPhase({ tag: 'wallet-connect' })
    setIssuerTab('tel')
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

      {role === 'issuer' && (
        <div className="tab-bar">
          <button
            className={`tab-btn ${issuerTab === 'tel' ? 'tab-btn--active' : ''}`}
            onClick={() => setIssuerTab('tel')}
          >
            Trusted Entities
          </button>
          <button
            className={`tab-btn ${issuerTab === 'allowlist' ? 'tab-btn--active' : ''}`}
            onClick={() => setIssuerTab('allowlist')}
          >
            Allow List
          </button>
        </div>
      )}

      <div id="view">
        {role === 'issuer' && issuerTab === 'tel' && (
          <IssuerDashboard wallet={wallet} walletPkh={phase.pkh} />
        )}
        {role === 'issuer' && issuerTab === 'allowlist' && (
          <AllowListDashboard wallet={wallet} walletPkh={phase.pkh} />
        )}
        {role === 'entity' && <AllowListDashboard wallet={wallet} walletPkh={phase.pkh} />}
        {role === 'user' && <UserFlow />}
      </div>
    </div>
  )
}