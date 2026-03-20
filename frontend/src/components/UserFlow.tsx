import { useState, useEffect, useRef, useCallback } from 'react'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
import { apiGet, apiPost } from '../api'
import { getAvailableWallets, connectWalletByKey, type ConnectedWallet } from '../wallet'
import CopyButton from './CopyButton'

type CredentialData = {
  role: string
  roleValue: number
  label: string
  attributes: Record<string, unknown>
}
type Step = 1 | 2 | 3 | 4 | 'done'

function camelToTitle(key: string): string {
  return key.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase()).trim()
}

const ROLE_LABELS: Record<number, string> = { 0: 'User', 1: 'Institutional', 2: 'vLEI' }

function getSessionId(): string {
  let id = sessionStorage.getItem('keri-session-id')
  if (!id) {
    id = crypto.randomUUID()
    sessionStorage.setItem('keri-session-id', id)
  }
  return id
}

export default function UserFlow() {
  const [step, setStep] = useState<Step>(1)
  const [loading, setLoading] = useState(true)
  const [credential, setCredential] = useState<CredentialData | null>(null)
  const [storedAddress, setStoredAddress] = useState<string | null>(null)
  const [allowListTxHash, setAllowListTxHash] = useState<string | null>(null)
  const stopScanRef = useRef<(() => void) | null>(null)

  // On mount: restore session state — skip ahead based on how far the user got
  useEffect(() => {
    const sessionId = getSessionId()
    apiGet('/api/keri/session', { headers: { 'X-Session-Id': sessionId } })
      .then(r => r.json())
      .then((data: {
        exists: boolean; hasCredential: boolean; hasCardanoAddress: boolean;
        attributes?: Record<string, unknown>; credentialRole?: number; credentialRoleName?: string;
        cardanoAddress?: string; allowListTxHash?: string
      }) => {
        if (data.hasCredential && data.attributes) {
          const roleValue = data.credentialRole ?? 0
          setCredential({
            role: data.credentialRoleName ?? 'USER',
            roleValue,
            label: ROLE_LABELS[roleValue] ?? 'User',
            attributes: data.attributes,
          })
          if (data.allowListTxHash) {
            // Already on the Allow List — jump straight to the done view
            setAllowListTxHash(data.allowListTxHash)
            setStep('done')
          } else {
            setStep(4)
          }
        }
        if (data.hasCardanoAddress && data.cardanoAddress) {
          setStoredAddress(data.cardanoAddress)
        }
      })
      .catch(() => { /* session missing or error — start from step 1 */ })
      .finally(() => setLoading(false))
  }, [])

  function goto(s: Step) {
    if (stopScanRef.current) { stopScanRef.current(); stopScanRef.current = null }
    setStep(s)
  }

  if (loading) {
    return <div className="card"><p className="status info">Loading…</p></div>
  }

  return (
    <div>
      <StepperHeader step={step} />
      <div>
        {step === 1 && <Step1 onNext={() => goto(2)} />}
        {step === 2 && <Step2 stopScanRef={stopScanRef} onNext={() => goto(3)} />}
        {step === 3 && (
          <Step3 onNext={(data) => { setCredential(data); goto(4) }} />
        )}
        {step === 4 && (
          <Step4
            credential={credential!}
            storedAddress={storedAddress}
            onAddressStored={(addr) => setStoredAddress(addr)}
            onNext={() => goto('done')}
            onUpdateCredentials={() => { setCredential(null); goto(1) }}
          />
        )}
        {step === 'done' && (
          <StepDone
            credential={credential}
            allowListTxHash={allowListTxHash}
            onRestart={() => { setCredential(null); setStoredAddress(null); setAllowListTxHash(null); goto(1) }}
          />
        )}
      </div>
    </div>
  )
}

function StepperHeader({ step }: { step: Step }) {
  const s = step === 'done' ? 5 : (step as number)
  return (
    <div className="stepper-header">
      <div className={`step-pill ${s >= 1 ? (s > 1 ? 'done' : 'active') : ''}`}>
        {s > 1 ? '✓' : '1'} Share OOBI
      </div>
      <div className="step-connector" />
      <div className={`step-pill ${s >= 2 ? (s > 2 ? 'done' : 'active') : ''}`}>
        {s > 2 ? '✓' : '2'} Scan OOBI
      </div>
      <div className="step-connector" />
      <div className={`step-pill ${s >= 3 ? (s > 3 ? 'done' : 'active') : ''}`}>
        {s > 3 ? '✓' : '3'} Present credential
      </div>
      <div className="step-connector" />
      <div className={`step-pill ${s >= 4 ? (s > 4 ? 'done' : 'active') : ''}`}>
        {s > 4 ? '✓' : '4'} Join Allow List
      </div>
    </div>
  )
}

function Step1({ onNext }: { onNext: () => void }) {
  const [oobi, setOobi] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const sessionId = getSessionId()

  useEffect(() => {
    apiGet('/api/keri/oobi', { headers: { 'X-Session-Id': sessionId } })
      .then(r => r.json())
      .then((d: { oobi?: string }) => {
        if (!d.oobi) throw new Error('No oobi in response')
        setOobi(d.oobi)
        setTimeout(() => {
          if (canvasRef.current) QRCode.toCanvas(canvasRef.current, d.oobi!, { width: 220, margin: 1 })
        }, 0)
      })
      .catch(e => setError((e as Error).message))
  }, [])

  return (
    <div className="card">
      <p className="card-title">Share your OOBI</p>
      <p className="card-subtitle">Let the other party scan or copy this OOBI to establish a connection.</p>
      {error && <p className="status err">Failed to fetch OOBI: {error}</p>}
      {oobi && (
        <>
          <div className="qr-wrapper"><canvas ref={canvasRef} /></div>
          <div className="copy-row">
            <input readOnly value={oobi} />
            <CopyButton text={oobi} />
          </div>
        </>
      )}
      {!oobi && !error && <p className="status info">Fetching OOBI…</p>}
      <button className="btn-primary" onClick={onNext} disabled={!oobi} style={{ marginTop: '1rem' }}>
        Next — Scan partner OOBI →
      </button>
    </div>
  )
}

function Step2({ stopScanRef, onNext }: {
  stopScanRef: React.MutableRefObject<(() => void) | null>
  onNext: () => void
}) {
  const [paste, setPaste] = useState('')
  const [camStatus, setCamStatus] = useState('Starting camera…')
  const [status, setStatus] = useState<{ type: string; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const videoRef = useRef<HTMLVideoElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const sessionId = getSessionId()

  const startCamera = useCallback(() => {
    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
      .then(stream => {
        const video = videoRef.current!
        video.srcObject = stream
        video.play()
        setCamStatus('Scanning…')
        let active = true
        stopScanRef.current = () => { active = false; stream.getTracks().forEach(t => t.stop()) }
        function scan() {
          if (!active) return
          if (video.readyState === video.HAVE_ENOUGH_DATA) {
            const canvas = canvasRef.current!
            canvas.width = video.videoWidth; canvas.height = video.videoHeight
            const ctx = canvas.getContext('2d')!
            ctx.drawImage(video, 0, 0)
            const img = ctx.getImageData(0, 0, canvas.width, canvas.height)
            const result = jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' })
            if (result?.data) {
              active = false; stream.getTracks().forEach(t => t.stop())
              setCamStatus('✓ QR detected'); setPaste(result.data); doResolve(result.data); return
            }
          }
          requestAnimationFrame(scan)
        }
        requestAnimationFrame(scan)
      })
      .catch(() => setCamStatus('Camera unavailable — use paste instead.'))
  }, [])

  useEffect(() => { startCamera() }, [startCamera])

  async function doResolve(val: string) {
    setBusy(true); setStatus({ type: 'info', msg: 'Resolving OOBI…' })
    try {
      const res = await apiGet(`/api/keri/oobi/resolve?oobi=${encodeURIComponent(val)}`, {
        headers: { 'X-Session-Id': sessionId },
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setStatus({ type: 'ok', msg: 'Connection established!' })
      setTimeout(onNext, 800)
    } catch (e) {
      setStatus({ type: 'err', msg: `Failed: ${e instanceof Error ? e.message : String(e)}` })
      setBusy(false)
    }
  }

  return (
    <div className="card">
      <p className="card-title">Scan partner OOBI</p>
      <p className="card-subtitle">Point the camera at the partner's QR code, or paste the OOBI string below.</p>
      <div className="scanner-wrap">
        <video ref={videoRef} autoPlay playsInline muted />
        <canvas ref={canvasRef} style={{ display: 'none' }} />
        <div className="scan-overlay"><div className="scan-box" /></div>
      </div>
      <p className={`status ${camStatus.startsWith('✓') ? 'ok' : camStatus.includes('unavailable') ? 'err' : 'info'}`}>
        {camStatus}
      </p>
      <div className="divider">or paste manually</div>
      <textarea className="paste-area" placeholder="Paste OOBI string here…"
        value={paste} onChange={e => setPaste(e.target.value)} />
      <button className="btn-primary" disabled={busy || !paste.trim()}
        onClick={() => doResolve(paste.trim())} style={{ marginTop: '.75rem' }}>
        Resolve OOBI →
      </button>
      {status && <p className={`status ${status.type}`}>{status.msg}</p>}
    </div>
  )
}

interface AvailableRole { role: string; roleValue: number; label: string }

function Step3({ onNext }: { onNext: (data: CredentialData) => void }) {
  const [availableRoles, setAvailableRoles] = useState<AvailableRole[] | null>(null)
  const [selectedRole, setSelectedRole] = useState<string | null>(null)
  const [loadErr, setLoadErr] = useState<string | null>(null)
  const [status, setStatus] = useState<{ type: string; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const sessionId = getSessionId()

  useEffect(() => {
    apiGet('/api/keri/available-roles')
      .then(r => r.json())
      .then((d: { availableRoles: AvailableRole[] }) => {
        setAvailableRoles(d.availableRoles ?? [])
        if (d.availableRoles?.length === 1) setSelectedRole(d.availableRoles[0].role)
      })
      .catch(e => setLoadErr((e as Error).message))
  }, [])

  async function present() {
    if (!selectedRole) return
    setBusy(true)
    setCancelling(false)
    setStatus({ type: 'info', msg: 'Requesting presentation — please approve in your wallet when prompted…' })
    try {
      const res = await apiGet(
        `/api/keri/credential/present?role=${encodeURIComponent(selectedRole)}`,
        { headers: { 'X-Session-Id': sessionId } }
      )
      if (!res.ok) {
        const body = await res.json().catch(() => ({})) as { error?: string }
        throw new Error(body.error ?? `HTTP ${res.status}`)
      }
      const raw = await res.json() as { role: string; roleValue: number; label: string; attributes: Record<string, unknown> }
      setStatus({ type: 'ok', msg: 'Presentation completed successfully.' })
      setTimeout(() => onNext(raw), 900)
    } catch (e) {
      setStatus({ type: 'err', msg: `${e instanceof Error ? e.message : String(e)}` })
      setBusy(false)
      setCancelling(false)
    }
  }

  async function cancel() {
    if (!busy || cancelling) return
    setCancelling(true)
    setStatus({ type: 'info', msg: 'Cancelling…' })
    try {
      await apiPost('/api/keri/credential/cancel', {}, { headers: { 'X-Session-Id': sessionId } })
    } catch {
      // Ignore errors here — present() will handle the resulting state
    }
  }

  return (
    <div className="card">
      <p className="card-title">Present KYC Credential</p>
      <p className="card-subtitle">
        Select the role for which you want to present a credential,
        then approve the presentation in your wallet.
      </p>

      {loadErr && <p className="status err">Failed to load available roles: {loadErr}</p>}

      {availableRoles === null && !loadErr && (
        <p className="status info">Loading available roles…</p>
      )}

      {availableRoles !== null && availableRoles.length === 0 && (
        <p className="status err">
          No roles are available. The signing entity may not be registered in the Trusted Entity List.
        </p>
      )}

      {availableRoles !== null && availableRoles.length > 0 && (
        <div className="role-cards">
          {availableRoles.map(r => (
            <button
              key={r.role}
              className={`role-card ${selectedRole === r.role ? 'role-card--selected' : ''}`}
              onClick={() => setSelectedRole(r.role)}
              disabled={busy}
            >
              <div className="role-card__info">
                <span className="role-card__title">{r.label}</span>
                <span className="role-card__desc">{r.role}</span>
              </div>
              <span className={`te-role-badge te-role-${r.roleValue}`}>
                {ROLE_LABELS[r.roleValue] ?? r.role}
              </span>
            </button>
          ))}
        </div>
      )}

      {status
        ? <p className={`status ${status.type}`}>{status.msg}</p>
        : selectedRole
          ? <p className="status info">Ready to request credential presentation.</p>
          : null}

      <div style={{ display: 'flex', gap: '.5rem', marginTop: '.75rem' }}>
        <button
          className="btn-primary"
          onClick={present}
          disabled={busy || !selectedRole}
        >
          {busy && !cancelling ? 'Requesting…' : 'Request Presentation →'}
        </button>
        {busy && (
          <button
            className="btn-icon"
            onClick={cancel}
            disabled={cancelling}
          >
            {cancelling ? 'Cancelling…' : 'Cancel'}
          </button>
        )}
      </div>
    </div>
  )
}

function Step4({
  credential, storedAddress, onAddressStored, onNext, onUpdateCredentials,
}: {
  credential: CredentialData
  storedAddress: string | null
  onAddressStored: (addr: string) => void
  onNext: () => void
  onUpdateCredentials: () => void
}) {
  const sessionId = getSessionId()
  const [wallet, setWallet] = useState<ConnectedWallet | null>(null)
  const [walletErr, setWalletErr] = useState<string | null>(null)
  const [status, setStatus] = useState<{ type: string; msg: string } | null>(null)
  const [txHash, setTxHash] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const availableWallets = getAvailableWallets()

  async function connectWallet(key: string) {
    setWalletErr(null)
    try {
      const w = await connectWalletByKey(key)
      setWallet(w)
      await apiPost('/api/keri/session/cardano-address',
        { cardanoAddress: w.changeAddress },
        { headers: { 'X-Session-Id': sessionId } }
      )
      onAddressStored(w.changeAddress)
    } catch (e) {
      setWalletErr(`Failed to connect: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  async function buildSignAndSubmit() {
    if (!wallet) return
    setBusy(true)
    setTxHash(null)
    try {
      // Step 1: build
      setStatus({ type: 'info', msg: 'Building transaction…' })
      const buildRes = await fetch('/api/keri/allowlist/build-add-tx', {
        method: 'POST',
        headers: { 'X-Session-Id': sessionId },
      })
      if (!buildRes.ok) {
        const err = await buildRes.json().catch(() => ({ error: `HTTP ${buildRes.status}` }))
        throw new Error(err.error ?? `HTTP ${buildRes.status}`)
      }
      const { txCbor } = await buildRes.json() as { txCbor: string }

      // Step 2: sign
      setStatus({ type: 'info', msg: 'Waiting for wallet signature…' })
      const witnessCbor = await wallet.api.signTx(txCbor, true)

      // Step 3: submit
      setStatus({ type: 'info', msg: 'Submitting transaction…' })
      const submitRes = await apiPost('/api/allowlist/submit', { unsignedTxCbor: txCbor, witnessCbor })
      if (!submitRes.ok) {
        const err = await submitRes.json().catch(() => ({ error: `HTTP ${submitRes.status}` }))
        throw new Error(err.error ?? `HTTP ${submitRes.status}`)
      }
      const { txHash: hash } = await submitRes.json() as { txHash: string }
      setTxHash(hash)
      setStatus({ type: 'ok', msg: 'Transaction submitted!' })
      // Persist the tx hash to the session so it survives page reload
      await apiPost('/api/keri/session/allowlist-tx',
        { txHash: hash },
        { headers: { 'X-Session-Id': sessionId } }
      ).catch(() => { /* non-critical — state already in memory */ })
      setTimeout(onNext, 1200)
    } catch (e) {
      setStatus({ type: 'err', msg: `${e instanceof Error ? e.message : String(e)}` })
      setBusy(false)
    }
  }

  const activeAddress = wallet?.changeAddress ?? storedAddress

  return (
    <div className="card">
      <p className="card-title">Join the Allow List</p>

      {/* KYC credential summary */}
      <div style={{ background: 'var(--surface-2,#f0f4f8)', borderRadius: 8, padding: '1rem', marginBottom: '1.25rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.5rem' }}>
            <span style={{ fontWeight: 600, fontSize: '.8rem', textTransform: 'uppercase', opacity: .55 }}>
              KYC Credential
            </span>
            <span className={`te-role-badge te-role-${credential.roleValue}`}>
              {credential.label || ROLE_LABELS[credential.roleValue] || credential.role}
            </span>
          </div>
          <button className="btn-icon" style={{ fontSize: '.8rem' }} onClick={onUpdateCredentials}>
            Update credentials
          </button>
        </div>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '.95rem' }}>
          <tbody>
            {Object.entries(credential.attributes).map(([key, val]) => (
              <tr key={key}>
                <td style={{ padding: '3px 0', opacity: .65, width: '36%' }}>{camelToTitle(key)}</td>
                <td style={{ padding: '3px 0', fontWeight: 500 }}>{val != null ? String(val) : '—'}</td>
              </tr>
            ))}
            {Object.keys(credential.attributes).length === 0 && (
              <tr><td colSpan={2} style={{ padding: '3px 0', opacity: .65 }}>No attributes available.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Cardano wallet section */}
      {!wallet ? (
        <div>
          {storedAddress && (
            <div className="status info" style={{ marginBottom: '.75rem' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '.35rem', flexWrap: 'wrap', marginBottom: '.2rem' }}>
                <span style={{ fontSize: '.85rem' }}>Registered address:</span>
                <code style={{ fontSize: '.8rem' }}>{storedAddress.slice(0, 22)}…</code>
                <CopyButton text={storedAddress} />
              </div>
              <span style={{ fontSize: '.83rem' }}>Connect your wallet to sign the transaction.</span>
            </div>
          )}
          {!storedAddress && (
            <p className="card-subtitle" style={{ marginBottom: '.75rem' }}>
              Connect your Cardano wallet to sign the Allow List transaction.
            </p>
          )}
          {availableWallets.length === 0 && (
            <p className="status err">No Cardano wallet extension detected. Please install one and reload.</p>
          )}
          <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'wrap' }}>
            {availableWallets.map(w => (
              <button key={w.key} className="btn-primary"
                style={{ display: 'flex', alignItems: 'center', gap: '.4rem' }}
                onClick={() => connectWallet(w.key)}>
                {w.icon && <img src={w.icon} alt="" style={{ width: 20, height: 20 }} />}
                {w.name}
              </button>
            ))}
          </div>
          {walletErr && <p className="status err" style={{ marginTop: '.5rem' }}>{walletErr}</p>}
        </div>
      ) : (
        <div>
          <div className="status ok" style={{ marginBottom: '1rem', display: 'flex', alignItems: 'center', gap: '.4rem', flexWrap: 'wrap' }}>
            <span>✓ {wallet.name} connected</span>
            {activeAddress && (
              <>
                <code style={{ fontSize: '.82rem' }}>{activeAddress.slice(0, 22)}…</code>
                <CopyButton text={activeAddress} />
              </>
            )}
          </div>

          <button className="btn-primary" onClick={buildSignAndSubmit} disabled={busy}>
            {busy ? 'Processing…' : 'Sign & Submit Transaction →'}
          </button>

          {status && (
            <div className={`status ${status.type}`} style={{ marginTop: '.75rem' }}>
              {status.msg}
              {status.type === 'ok' && txHash && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '.35rem', marginTop: '.35rem' }}>
                  <span style={{ fontSize: '.78rem', opacity: .7 }}>Tx:</span>
                  <code style={{ fontSize: '.78rem' }}>{txHash.slice(0, 20)}…</code>
                  <CopyButton text={txHash} />
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function StepDone({ credential, allowListTxHash, onRestart }: {
  credential: CredentialData | null
  allowListTxHash: string | null
  onRestart: () => void
}) {
  return (
    <div className="card">
      <p className="card-title">Allow List Status</p>

      <div className="status ok" style={{ display: 'flex', alignItems: 'center', gap: '.5rem', marginBottom: '1.25rem' }}>
        <span style={{ fontWeight: 600 }}>✓ You are on the Allow List</span>
      </div>

      {/* Allow List tx hash */}
      {allowListTxHash && (
        <div style={{ marginBottom: '1.25rem' }}>
          <span style={{ fontWeight: 600, fontSize: '.8rem', textTransform: 'uppercase', opacity: .55 }}>
            Allow List Transaction
          </span>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.4rem', marginTop: '.35rem' }}>
            <code style={{ fontSize: '.85rem' }}>{allowListTxHash.slice(0, 20)}…{allowListTxHash.slice(-8)}</code>
            <CopyButton text={allowListTxHash} />
          </div>
        </div>
      )}

      {/* KYC credential summary */}
      {credential && (
        <div style={{ background: 'var(--surface-2,#f0f4f8)', borderRadius: 8, padding: '1rem', marginBottom: '1.25rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.5rem', marginBottom: '.5rem' }}>
            <span style={{ fontWeight: 600, fontSize: '.8rem', textTransform: 'uppercase', opacity: .55 }}>
              KYC Credential
            </span>
            <span className={`te-role-badge te-role-${credential.roleValue}`}>
              {credential.label || ROLE_LABELS[credential.roleValue] || credential.role}
            </span>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '.95rem' }}>
            <tbody>
              {Object.entries(credential.attributes).map(([key, val]) => (
                <tr key={key}>
                  <td style={{ padding: '3px 0', opacity: .65, width: '36%' }}>{camelToTitle(key)}</td>
                  <td style={{ padding: '3px 0', fontWeight: 500 }}>{val != null ? String(val) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <button className="btn-icon" onClick={onRestart} style={{ marginTop: '.25rem' }}>
        Start over
      </button>
    </div>
  )
}

