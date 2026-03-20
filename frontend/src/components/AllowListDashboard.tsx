import { useState, useEffect, useCallback } from 'react'
import { type ConnectedWallet, signTx } from '../wallet'
import { apiGet, apiPost } from '../api'
import CopyButton from './CopyButton'

interface Props {
  wallet: ConnectedWallet
  walletPkh: string
}

type WlStatus =
  | { initialised: false }
  | { initialised: true; scriptAddress: string; policyId: string }

interface WlMember {
  nodePolicyId: string
  txHash: string
  outputIndex: number
  pkh: string
  active: boolean
  role: number
  roleName: string
}

const WL_ROLE_LABELS: Record<number, string> = { 0: 'User', 1: 'Institutional', 2: 'vLEI' }

type OpStatus =
  | { type: 'idle' }
  | { type: 'info'; msg: string }
  | { type: 'ok'; msg: string }
  | { type: 'err'; msg: string }

export default function AllowListDashboard({ wallet, walletPkh }: Props) {
  const [wlStatus, setWlStatus] = useState<WlStatus | null>(null)
  const [members, setMembers] = useState<WlMember[]>([])
  const [loadingStatus, setLoadingStatus] = useState(true)
  const [loadingMembers, setLoadingMembers] = useState(false)

  const loadStatus = useCallback(async () => {
    setLoadingStatus(true)
    try {
      const res = await apiGet('/api/allowlist/status')
      const data = await res.json() as WlStatus
      setWlStatus(data)
      if (data.initialised) loadMembers()
    } finally {
      setLoadingStatus(false)
    }
  }, [])

  async function loadMembers() {
    setLoadingMembers(true)
    try {
      const res = await apiGet('/api/allowlist/members')
      setMembers(await res.json() as WlMember[])
    } finally {
      setLoadingMembers(false)
    }
  }

  useEffect(() => { loadStatus() }, [loadStatus])

  if (loadingStatus) {
    return <div className="card"><p className="status info" style={{ textAlign: 'center', padding: '1.5rem' }}>Loading Allow List status…</p></div>
  }
  if (!wlStatus) {
    return <div className="card"><p className="status err">Failed to reach backend.</p></div>
  }

  return (
    <div className="issuer-dashboard">
      <WlStatusCard
        status={wlStatus}
        wallet={wallet}
        onInitialised={() => setTimeout(loadStatus, 5000)}
      />
      {wlStatus.initialised && (
        <WlMembersList
          members={members}
          loading={loadingMembers}
          wallet={wallet}
          walletPkh={walletPkh}
          onRefresh={loadMembers}
          onChanged={() => setTimeout(loadMembers, 8000)}
        />
      )}
    </div>
  )
}

// ── Status / Init Card ────────────────────────────────────────────────────────

function WlStatusCard({ status, wallet, onInitialised }: {
  status: WlStatus
  wallet: ConnectedWallet
  onInitialised: () => void
}) {
  const [opStatus, setOpStatus] = useState<OpStatus>({ type: 'idle' })
  const [lastTxHash, setLastTxHash] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function doInit() {
    setBusy(true)
    setOpStatus({ type: 'info', msg: 'Building Allow List Init transaction…' })
    try {
      const buildRes = await apiPost('/api/allowlist/build-init', { entityAddress: wallet.changeAddress })
      const buildData = await buildRes.json()
      if (!buildRes.ok) throw new Error(buildData.error ?? `HTTP ${buildRes.status}`)

      setOpStatus({ type: 'info', msg: 'Please approve the transaction in your wallet…' })
      const witnessCbor = await signTx(wallet, buildData.txCbor)

      setOpStatus({ type: 'info', msg: 'Submitting transaction…' })
      const submitRes = await apiPost('/api/allowlist/submit', { unsignedTxCbor: buildData.txCbor, witnessCbor })
      const submitData = await submitRes.json()
      if (!submitRes.ok) throw new Error(submitData.error ?? `HTTP ${submitRes.status}`)

      setOpStatus({ type: 'info', msg: 'Registering Allow List config…' })
      await apiPost('/api/allowlist/register', { bootTxHash: buildData.bootTxHash, bootIndex: buildData.bootIndex })

      setLastTxHash(submitData.txHash as string)
      setOpStatus({ type: 'ok', msg: 'Allow List initialised!' })
      onInitialised()
    } catch (e) {
      setOpStatus({ type: 'err', msg: `Failed: ${e instanceof Error ? e.message : String(e)}` })
      setBusy(false)
    }
  }

  if (!status.initialised) {
    return (
      <div className="card tel-card">
        <p className="card-title">Allow List</p>
        <p className="card-subtitle">
          No Allow List exists yet. Initialise one to start managing KYC entries.
          Requires the Trusted Entity List to be active first.
        </p>
        <button className="btn-primary" onClick={doInit} disabled={busy}>
          {busy ? 'Building transaction…' : 'Initialise Allow List'}
        </button>
        {opStatus.type !== 'idle' && (
          <div className={`status ${opStatus.type}`}>
            {opStatus.msg}
            {opStatus.type === 'ok' && lastTxHash && (
              <div style={{ display: 'flex', alignItems: 'center', gap: '.35rem', marginTop: '.35rem' }}>
                <span style={{ fontSize: '.78rem', opacity: .7 }}>Tx:</span>
                <code style={{ fontSize: '.78rem' }}>{lastTxHash.slice(0, 20)}…</code>
                <CopyButton text={lastTxHash} />
              </div>
            )}
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="card tel-card tel-status-card">
      <div className="tel-status-row">
        <p className="card-title" style={{ margin: 0 }}>Allow List</p>
        <span className="tel-badge tel-badge-ok">Active</span>
      </div>
      <div className="tel-meta">
        <div className="tel-meta-item">
          <span className="tel-meta-label">Policy</span>
          <code className="tel-mono">{status.policyId.slice(0, 20)}…</code>
          <CopyButton text={status.policyId} />
        </div>
        <div className="tel-meta-item">
          <span className="tel-meta-label">Script</span>
          <code className="tel-mono">{status.scriptAddress.slice(0, 24)}…</code>
          <CopyButton text={status.scriptAddress} />
        </div>
      </div>
    </div>
  )
}

// ── Members List ──────────────────────────────────────────────────────────────

function WlMembersList({ members, loading, wallet, walletPkh, onRefresh, onChanged }: {
  members: WlMember[]
  loading: boolean
  wallet: ConnectedWallet
  walletPkh: string
  onRefresh: () => void
  onChanged: () => void
}) {
  const [togglingPkh, setTogglingPkh] = useState<string | null>(null)
  const [toggleStatus, setToggleStatus] = useState<Record<string, OpStatus>>({})
  const [toggleTxHash, setToggleTxHash] = useState<Record<string, string>>({})

  async function doToggle(member: WlMember) {
    const newActive = !member.active
    setTogglingPkh(member.pkh)
    setToggleStatus(s => ({ ...s, [member.pkh]: { type: 'info', msg: `Setting active=${newActive}…` } }))
    try {
      const buildRes = await apiPost('/api/allowlist/build-set-active', {
        userPkh: member.pkh,
        newActive,
        entityAddress: wallet.changeAddress,
      })
      const buildData = await buildRes.json()
      if (!buildRes.ok) throw new Error(buildData.error ?? `HTTP ${buildRes.status}`)

      setToggleStatus(s => ({ ...s, [member.pkh]: { type: 'info', msg: 'Please approve in your wallet…' } }))
      const witnessCbor = await signTx(wallet, buildData.txCbor)

      setToggleStatus(s => ({ ...s, [member.pkh]: { type: 'info', msg: 'Submitting…' } }))
      const submitRes = await apiPost('/api/allowlist/submit', { unsignedTxCbor: buildData.txCbor, witnessCbor })
      const submitData = await submitRes.json()
      if (!submitRes.ok) throw new Error(submitData.error ?? `HTTP ${submitRes.status}`)

      setToggleTxHash(s => ({ ...s, [member.pkh]: submitData.txHash as string }))
      setToggleStatus(s => ({ ...s, [member.pkh]: { type: 'ok', msg: 'Updated!' } }))
      onChanged()
    } catch (e) {
      setToggleStatus(s => ({
        ...s,
        [member.pkh]: { type: 'err', msg: `Failed: ${e instanceof Error ? e.message : String(e)}` }
      }))
    } finally {
      setTogglingPkh(null)
    }
  }

  return (
    <div className="card tel-card">
      <div className="tel-status-row">
        <p className="card-title" style={{ margin: 0 }}>Members</p>
        <button className="btn-icon" onClick={onRefresh} disabled={loading}>
          {loading ? '…' : 'Refresh'}
        </button>
      </div>

      {loading ? (
        <p className="status info" style={{ textAlign: 'center', marginTop: '.75rem' }}>Loading…</p>
      ) : members.length === 0 ? (
        <p className="status info" style={{ textAlign: 'center', marginTop: '.75rem' }}>
          No members yet.
        </p>
      ) : (
        <div className="members-list">
          {members.map((m) => (
            <WlMemberCard
              key={m.pkh}
              member={m}
              isOwn={m.pkh.toLowerCase() === walletPkh.toLowerCase()}
              toggling={togglingPkh === m.pkh}
              status={toggleStatus[m.pkh]}
              lastTxHash={toggleTxHash[m.pkh]}
              onToggle={() => doToggle(m)}
              anyToggling={togglingPkh !== null}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function WlMemberCard({ member, isOwn, toggling, status, lastTxHash, onToggle, anyToggling }: {
  member: WlMember
  isOwn: boolean
  toggling: boolean
  status: OpStatus | undefined
  lastTxHash: string | undefined
  onToggle: () => void
  anyToggling: boolean
}) {
  return (
    <div className={`member-card ${toggling ? 'member-card--removing' : ''}`}>
      <div className="member-card-header">
        <div className="member-details">
          <div style={{ display: 'flex', alignItems: 'center', gap: '.4rem', marginBottom: '.15rem' }}>
            {isOwn && <span className="member-own-badge">Your wallet</span>}
            <span className={`te-role-badge te-role-${member.role ?? 0}`}>
              {WL_ROLE_LABELS[member.role ?? 0] ?? member.roleName ?? 'User'}
            </span>
          </div>
          <div className="member-vkey">
            <span className="member-label">PKH</span>
            <code className="tel-mono">{member.pkh.slice(0, 16)}…{member.pkh.slice(-8)}</code>
            <CopyButton text={member.pkh} />
          </div>
          {member.txHash && (
            <div className="member-utxo">
              <span className="member-label">UTxO</span>
              <code className="tel-mono">{member.txHash.slice(0, 10)}…#{member.outputIndex}</code>
              <CopyButton text={`${member.txHash}#${member.outputIndex}`} />
            </div>
          )}
        </div>
        <div className="member-actions wl-member-actions">
          <span className={`wl-active-badge ${member.active ? 'wl-active-badge--on' : 'wl-active-badge--off'}`}>
            {member.active ? 'Active' : 'Inactive'}
          </span>
          <button
            className={member.active ? 'btn-ghost-sm' : 'btn-primary-sm'}
            onClick={onToggle}
            disabled={anyToggling}
            title={member.active ? 'Deactivate' : 'Activate'}
          >
            {toggling ? '…' : member.active ? 'Deactivate' : 'Activate'}
          </button>
        </div>
      </div>
      {status && status.type !== 'idle' && (
        <div className={`status ${status.type} member-status`}>
          {status.msg}
          {status.type === 'ok' && lastTxHash && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '.35rem', marginTop: '.3rem' }}>
              <span style={{ fontSize: '.75rem', opacity: .7 }}>Tx:</span>
              <code style={{ fontSize: '.75rem' }}>{lastTxHash.slice(0, 20)}…</code>
              <CopyButton text={lastTxHash} />
            </div>
          )}
        </div>
      )}
    </div>
  )
}