import { useState, useEffect, useCallback } from 'react'
import { type ConnectedWallet, signTx } from '../wallet'
import { apiGet, apiPost } from '../api'
import CopyButton from './CopyButton'

interface Props {
  wallet: ConnectedWallet
  walletPkh: string
}

type TelStatus =
  | { initialised: false }
  | { initialised: true; scriptAddress: string; policyId: string; issuerVkey: string }

interface TeNode {
  id: number
  vkey: string
  nodePolicyId: string
  txHash: string
  outputIndex: number
  pkh: string
}

type OpStatus =
  | { type: 'idle' }
  | { type: 'info'; msg: string }
  | { type: 'ok'; msg: string }
  | { type: 'err'; msg: string }

export default function IssuerDashboard({ wallet, walletPkh }: Props) {
  const [telStatus, setTelStatus] = useState<TelStatus | null>(null)
  const [members, setMembers] = useState<TeNode[]>([])
  const [loadingStatus, setLoadingStatus] = useState(true)
  const [loadingMembers, setLoadingMembers] = useState(false)

  const loadStatus = useCallback(async () => {
    setLoadingStatus(true)
    try {
      const res = await apiGet('/api/tel/status')
      const data = await res.json() as TelStatus
      setTelStatus(data)
      if (data.initialised) loadMembers()
    } finally {
      setLoadingStatus(false)
    }
  }, [])

  async function loadMembers() {
    setLoadingMembers(true)
    try {
      const res = await apiGet('/api/tel/members')
      setMembers(await res.json() as TeNode[])
    } finally {
      setLoadingMembers(false)
    }
  }

  useEffect(() => { loadStatus() }, [loadStatus])

  if (loadingStatus) {
    return <div className="card"><p className="status info" style={{ textAlign: 'center', padding: '1.5rem' }}>Loading TEL status…</p></div>
  }

  if (!telStatus) {
    return <div className="card"><p className="status err">Failed to reach backend.</p></div>
  }

  return (
    <div className="issuer-dashboard">
      <TelStatusCard
        status={telStatus}
        wallet={wallet}
        onInitialised={() => setTimeout(loadStatus, 5000)}
      />
      {telStatus.initialised && (
        <>
          <TelMembersList
            members={members}
            loading={loadingMembers}
            wallet={wallet}
            walletPkh={walletPkh}
            onRefresh={loadMembers}
            onMemberRemoved={() => setTimeout(loadMembers, 8000)}
          />
          <AddEntityCard
            wallet={wallet}
            onAdded={() => setTimeout(loadMembers, 8000)}
          />
        </>
      )}
    </div>
  )
}

// ── TEL Status / Init Card ────────────────────────────────────────────────────

function TelStatusCard({ status, wallet, onInitialised }: {
  status: TelStatus
  wallet: ConnectedWallet
  onInitialised: () => void
}) {
  const [opStatus, setOpStatus] = useState<OpStatus>({ type: 'idle' })
  const [lastTxHash, setLastTxHash] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function doInit() {
    setBusy(true)
    setOpStatus({ type: 'info', msg: 'Building TEL Init transaction…' })
    try {
      const buildRes = await apiPost('/api/tel/build-init', { issuerAddress: wallet.changeAddress })
      const buildData = await buildRes.json()
      if (!buildRes.ok) throw new Error(buildData.error ?? `HTTP ${buildRes.status}`)

      setOpStatus({ type: 'info', msg: 'Please approve the transaction in your wallet…' })
      const witnessCbor = await signTx(wallet, buildData.txCbor)

      setOpStatus({ type: 'info', msg: 'Submitting transaction…' })
      const submitRes = await apiPost('/api/tel/submit', { unsignedTxCbor: buildData.txCbor, witnessCbor })
      const submitData = await submitRes.json()
      if (!submitRes.ok) throw new Error(submitData.error ?? `HTTP ${submitRes.status}`)

      setOpStatus({ type: 'info', msg: 'Registering TEL config…' })
      await apiPost('/api/tel/register', { bootTxHash: buildData.bootTxHash, bootIndex: buildData.bootIndex })

      setLastTxHash(submitData.txHash as string)
      setOpStatus({ type: 'ok', msg: 'TEL initialised!' })
      onInitialised()
    } catch (e) {
      setOpStatus({ type: 'err', msg: `Failed: ${e instanceof Error ? e.message : String(e)}` })
      setBusy(false)
    }
  }

  if (!status.initialised) {
    return (
      <div className="card tel-card">
        <p className="card-title">Trusted Entity List</p>
        <p className="card-subtitle">
          No Trusted Entity List exists on-chain yet. Initialise one to become the issuer.
          Your verification key will be registered as the first member.
        </p>
        <button className="btn-primary" onClick={doInit} disabled={busy}>
          {busy ? 'Building transaction…' : 'Initialise TEL'}
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
        <p className="card-title" style={{ margin: 0 }}>Trusted Entity List</p>
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

// ── TEL Members List ──────────────────────────────────────────────────────────

function TelMembersList({ members, loading, wallet, walletPkh, onRefresh, onMemberRemoved }: {
  members: TeNode[]
  loading: boolean
  wallet: ConnectedWallet
  walletPkh: string
  onRefresh: () => void
  onMemberRemoved: () => void
}) {
  const [removingVkey, setRemovingVkey] = useState<string | null>(null)
  const [removeStatus, setRemoveStatus] = useState<Record<string, OpStatus>>({})
  const [removeTxHash, setRemoveTxHash] = useState<Record<string, string>>({})

  async function doRemove(node: TeNode) {
    setRemovingVkey(node.vkey)
    setRemoveStatus(s => ({ ...s, [node.vkey]: { type: 'info', msg: 'Building remove transaction…' } }))
    try {
      const buildRes = await apiPost('/api/tel/build-remove', {
        entityVkeyHex: node.vkey,
        issuerAddress: wallet.changeAddress,
      })
      const buildData = await buildRes.json()
      if (!buildRes.ok) throw new Error(buildData.error ?? `HTTP ${buildRes.status}`)

      setRemoveStatus(s => ({ ...s, [node.vkey]: { type: 'info', msg: 'Please approve in your wallet…' } }))
      const witnessCbor = await signTx(wallet, buildData.txCbor)

      setRemoveStatus(s => ({ ...s, [node.vkey]: { type: 'info', msg: 'Submitting…' } }))
      const submitRes = await apiPost('/api/tel/submit', { unsignedTxCbor: buildData.txCbor, witnessCbor })
      const submitData = await submitRes.json()
      if (!submitRes.ok) throw new Error(submitData.error ?? `HTTP ${submitRes.status}`)

      setRemoveTxHash(s => ({ ...s, [node.vkey]: submitData.txHash as string }))
      setRemoveStatus(s => ({ ...s, [node.vkey]: { type: 'ok', msg: 'Removed!' } }))
      onMemberRemoved()
    } catch (e) {
      setRemoveStatus(s => ({
        ...s,
        [node.vkey]: { type: 'err', msg: `Failed: ${e instanceof Error ? e.message : String(e)}` }
      }))
    } finally {
      setRemovingVkey(null)
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
          No members indexed yet — chain may still be syncing.
        </p>
      ) : (
        <div className="members-list">
          {members.map((m, i) => (
            <MemberCard
              key={m.vkey}
              member={m}
              index={i}
              isOwn={m.pkh.toLowerCase() === walletPkh.toLowerCase()}
              removing={removingVkey === m.vkey}
              status={removeStatus[m.vkey]}
              lastTxHash={removeTxHash[m.vkey]}
              onRemove={() => doRemove(m)}
              anyRemoving={removingVkey !== null}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function MemberCard({ member, index, isOwn, removing, status, lastTxHash, onRemove, anyRemoving }: {
  member: TeNode
  index: number
  isOwn: boolean
  removing: boolean
  status: OpStatus | undefined
  lastTxHash: string | undefined
  onRemove: () => void
  anyRemoving: boolean
}) {
  const [confirmOpen, setConfirmOpen] = useState(false)

  return (
    <div className={`member-card ${removing ? 'member-card--removing' : ''}`}>
      <div className="member-card-header">
        <div className="member-index">#{index + 1}</div>
        <div className="member-details">
          {isOwn && <span className="member-own-badge">Your wallet</span>}
          <div className="member-vkey">
            <span className="member-label">Vkey</span>
            <code className="tel-mono">{member.vkey.slice(0, 16)}…{member.vkey.slice(-8)}</code>
            <CopyButton text={member.vkey} />
          </div>
          {member.txHash && (
            <div className="member-utxo">
              <span className="member-label">UTxO</span>
              <code className="tel-mono">{member.txHash.slice(0, 10)}…#{member.outputIndex}</code>
              <CopyButton text={`${member.txHash}#${member.outputIndex}`} />
            </div>
          )}
        </div>
        <div className="member-actions">
          {!confirmOpen ? (
            <button
              className="btn-danger-ghost"
              onClick={() => setConfirmOpen(true)}
              disabled={anyRemoving}
              title="Remove from TEL"
            >
              Remove
            </button>
          ) : (
            <div className="confirm-row">
              <span className="confirm-label">Confirm?</span>
              <button
                className="btn-danger"
                onClick={() => { setConfirmOpen(false); onRemove() }}
                disabled={removing}
              >
                Yes
              </button>
              <button
                className="btn-ghost-sm"
                onClick={() => setConfirmOpen(false)}
              >
                No
              </button>
            </div>
          )}
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

// ── Add Entity Card ───────────────────────────────────────────────────────────

function AddEntityCard({ wallet, onAdded }: {
  wallet: ConnectedWallet
  onAdded: () => void
}) {
  const [vkey, setVkey] = useState('')
  const [opStatus, setOpStatus] = useState<OpStatus>({ type: 'idle' })
  const [lastTxHash, setLastTxHash] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function doAdd() {
    if (!/^[0-9a-fA-F]{64}$/.test(vkey)) {
      setOpStatus({ type: 'err', msg: 'Please enter a valid 64-character hex vkey.' })
      return
    }
    setBusy(true)
    setOpStatus({ type: 'info', msg: 'Building TEL Add transaction…' })
    try {
      const buildRes = await apiPost('/api/tel/build-add', {
        entityVkeyHex: vkey,
        issuerAddress: wallet.changeAddress,
      })
      const buildData = await buildRes.json()
      if (!buildRes.ok) throw new Error(buildData.error ?? `HTTP ${buildRes.status}`)

      setOpStatus({ type: 'info', msg: 'Please approve the transaction in your wallet…' })
      const witnessCbor = await signTx(wallet, buildData.txCbor)

      setOpStatus({ type: 'info', msg: 'Submitting…' })
      const submitRes = await apiPost('/api/tel/submit', { unsignedTxCbor: buildData.txCbor, witnessCbor })
      const submitData = await submitRes.json()
      if (!submitRes.ok) throw new Error(submitData.error ?? `HTTP ${submitRes.status}`)

      setLastTxHash(submitData.txHash as string)
      setOpStatus({ type: 'ok', msg: 'Entity added — will appear after chain indexing.' })
      setVkey('')
      onAdded()
    } catch (e) {
      setOpStatus({ type: 'err', msg: `Failed: ${e instanceof Error ? e.message : String(e)}` })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card tel-card">
      <p className="card-title">Add Trusted Entity</p>
      <p className="card-subtitle">
        Enter the entity's 32-byte Ed25519 verification key (64 hex characters).
      </p>
      <input
        type="text"
        className="tel-input"
        placeholder="Entity vkey hex (64 characters)"
        maxLength={64}
        spellCheck={false}
        autoComplete="off"
        value={vkey}
        onChange={e => setVkey(e.target.value)}
      />
      <div className="vkey-length-hint">
        {vkey.length}/64 characters
        {vkey.length === 64 && /^[0-9a-fA-F]{64}$/.test(vkey) && (
          <span className="vkey-valid"> ✓ valid</span>
        )}
      </div>
      <button className="btn-primary" onClick={doAdd} disabled={busy || vkey.length !== 64}>
        {busy ? 'Building transaction…' : 'Add to TEL'}
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