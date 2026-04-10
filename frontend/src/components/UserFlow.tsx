import { useState, useEffect, useRef, useCallback } from 'react'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
import { apiGet, apiPost } from '../api'
import { getFundedAddress, type ConnectedWallet } from '../wallet'
import CopyButton from './CopyButton'

type CredentialData = {
  role: string
  roleValue: number
  label: string
  attributes: Record<string, unknown>
}

type KycProof = {
  payloadHex: string
  signatureHex: string
  entityVkeyHex: string
  entityTelUtxoRef: string
  telPolicyId: string
  validUntilPosixMs: number
  role: number
  roleName: string
}

type Step = 1 | 2 | 3 | 'done'

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

export default function UserFlow({ wallet }: { wallet: ConnectedWallet }) {
  const [step, setStep] = useState<Step>(1)
  const [loading, setLoading] = useState(true)
  const [credential, setCredential] = useState<CredentialData | null>(null)
  const [kycProof, setKycProof] = useState<KycProof | null>(null)
  const stopScanRef = useRef<(() => void) | null>(null)

  // On mount: restore session state — skip ahead based on how far the user got
  useEffect(() => {
    const sessionId = getSessionId()
    apiGet('/api/keri/session', { headers: { 'X-Session-Id': sessionId } })
      .then(r => r.json())
      .then((data: {
        exists: boolean; hasCredential: boolean; hasCardanoAddress: boolean;
        attributes?: Record<string, unknown>; credentialRole?: number; credentialRoleName?: string;
        cardanoAddress?: string; kycProofPayload?: string; kycProofSignature?: string;
        kycProofEntityVkey?: string; kycProofTelUtxoRef?: string; kycProofTelPolicyId?: string;
        kycProofValidUntil?: number
      }) => {
        if (data.hasCredential && data.attributes) {
          const roleValue = data.credentialRole ?? 0
          setCredential({
            role: data.credentialRoleName ?? 'USER',
            roleValue,
            label: ROLE_LABELS[roleValue] ?? 'User',
            attributes: data.attributes,
          })
          if (data.kycProofPayload) {
            setKycProof({
              payloadHex: data.kycProofPayload,
              signatureHex: data.kycProofSignature!,
              entityVkeyHex: data.kycProofEntityVkey!,
              entityTelUtxoRef: data.kycProofTelUtxoRef!,
              telPolicyId: data.kycProofTelPolicyId ?? '',
              validUntilPosixMs: data.kycProofValidUntil ?? 0,
              role: roleValue,
              roleName: data.credentialRoleName ?? 'USER',
            })
            setStep('done')
          } else {
            // Have credential but no proof — go to step 3 to auto-generate
            setStep(3)
          }
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
          <Step3
            wallet={wallet}
            existingCredential={credential}
            onComplete={(data, proof) => { setCredential(data); setKycProof(proof); goto('done') }}
          />
        )}
        {step === 'done' && (
          <StepDone
            credential={credential}
            kycProof={kycProof}
            onRestart={() => { setCredential(null); setKycProof(null); goto(1) }}
          />
        )}
      </div>
    </div>
  )
}

function StepperHeader({ step }: { step: Step }) {
  const s = step === 'done' ? 4 : (step as number)
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
        {s > 3 ? '✓' : '3'} Get KYC Proof
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

function Step3({ wallet, onComplete, existingCredential }: {
  wallet: ConnectedWallet
  onComplete: (data: CredentialData, proof: KycProof) => void
  existingCredential: CredentialData | null
}) {
  const [availableRoles, setAvailableRoles] = useState<AvailableRole[] | null>(null)
  const [selectedRole, setSelectedRole] = useState<string | null>(null)
  const [loadErr, setLoadErr] = useState<string | null>(null)
  const [status, setStatus] = useState<{ type: string; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [cancelling, setCancelling] = useState(false)

  // "I don't have a credential" form
  const [showIssueForm, setShowIssueForm] = useState(false)
  const [issueForm, setIssueForm] = useState({ firstName: '', lastName: '', email: '' })
  const [issueStatus, setIssueStatus] = useState<{ type: string; msg: string } | null>(null)
  const [issueBusy, setIssueBusy] = useState(false)

  const sessionId = getSessionId()
  const autoGenerateRef = useRef(false)

  useEffect(() => {
    if (!existingCredential) {
      apiGet('/api/keri/available-roles')
        .then(r => r.json())
        .then((d: { availableRoles: AvailableRole[] }) => {
          setAvailableRoles(d.availableRoles ?? [])
          if (d.availableRoles?.length === 1) setSelectedRole(d.availableRoles[0].role)
        })
        .catch(e => setLoadErr((e as Error).message))
    }
  }, [])

  // Auto-generate proof if we already have the credential (session restore)
  useEffect(() => {
    if (existingCredential && !autoGenerateRef.current) {
      autoGenerateRef.current = true
      setBusy(true)
      generateProof(existingCredential)
    }
  }, [existingCredential])

  async function generateProof(credData: CredentialData) {
    setStatus({ type: 'info', msg: 'Generating KYC proof…' })
    try {
      // Ensure the wallet address is stored in the session before generating
      const addr = await getFundedAddress(wallet)
      await apiPost('/api/keri/session/cardano-address',
        { cardanoAddress: addr },
        { headers: { 'X-Session-Id': sessionId } }
      )
      const res = await fetch('/api/keri/kyc-proof/generate', {
        method: 'POST',
        headers: { 'X-Session-Id': sessionId },
      })
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: `HTTP ${res.status}` })) as { error?: string }
        throw new Error(err.error ?? `HTTP ${res.status}`)
      }
      const proof = await res.json() as KycProof
      setStatus({ type: 'ok', msg: 'KYC proof generated!' })
      setTimeout(() => onComplete(credData, proof), 800)
    } catch (e) {
      setStatus({ type: 'err', msg: `Proof generation failed: ${e instanceof Error ? e.message : String(e)}` })
      setBusy(false)
    }
  }

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
      // Auto-generate proof after credential is verified
      setTimeout(() => generateProof(raw), 900)
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

  async function issueCredential() {
    const { firstName, lastName, email } = issueForm
    if (!firstName.trim() || !lastName.trim() || !email.trim()) {
      setIssueStatus({ type: 'err', msg: 'All fields are required.' })
      return
    }
    setIssueBusy(true)
    setIssueStatus({ type: 'info', msg: 'Issuing credential…' })
    try {
      const res = await apiPost(
        '/api/keri/credential/issue',
        { firstName: firstName.trim(), lastName: lastName.trim(), email: email.trim() },
        { headers: { 'X-Session-Id': sessionId } }
      )
      if (!res.ok) {
        const body = await res.json().catch(() => ({})) as { error?: string }
        throw new Error(body.error ?? `HTTP ${res.status}`)
      }
      await res.json()
      setIssueStatus({ type: 'ok', msg: 'Credential issued! Please now present it using the button above to confirm receipt.' })
      setShowIssueForm(false)
      setIssueBusy(false)
    } catch (e) {
      setIssueStatus({ type: 'err', msg: `${e instanceof Error ? e.message : String(e)}` })
      setIssueBusy(false)
    }
  }

  // Session restore: already have credential, auto-generating proof
  if (existingCredential) {
    return (
      <div className="card">
        <p className="card-title">Get KYC Proof</p>
        <div style={{ background: 'var(--surface-2,#f0f4f8)', borderRadius: 8, padding: '1rem', marginBottom: '1.25rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.5rem', marginBottom: '.5rem' }}>
            <span style={{ fontWeight: 600, fontSize: '.8rem', textTransform: 'uppercase', opacity: .55 }}>
              KYC Credential
            </span>
            <span className={`te-role-badge te-role-${existingCredential.roleValue}`}>
              {existingCredential.label || ROLE_LABELS[existingCredential.roleValue] || existingCredential.role}
            </span>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '.95rem' }}>
            <tbody>
              {Object.entries(existingCredential.attributes).map(([key, val]) => (
                <tr key={key}>
                  <td style={{ padding: '3px 0', opacity: .65, width: '36%' }}>{camelToTitle(key)}</td>
                  <td style={{ padding: '3px 0', fontWeight: 500 }}>{val != null ? String(val) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {status && <p className={`status ${status.type}`}>{status.msg}</p>}
        {!busy && status?.type === 'err' && (
          <button className="btn-primary" onClick={() => { setBusy(true); generateProof(existingCredential) }}
            style={{ marginTop: '.75rem' }}>
            Retry →
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="card">
      <p className="card-title">Get KYC Proof</p>
      <p className="card-subtitle">
        Present your KYC credential and a signed proof will be generated automatically.
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

      {/* ── I don't have a credential ─────────────────────────────────────── */}
      <div className="divider" style={{ margin: '1.5rem 0 1rem' }}>or</div>

      <button
        className="btn-icon"
        style={{ fontWeight: 600, marginBottom: showIssueForm ? '.75rem' : 0 }}
        onClick={() => setShowIssueForm(v => !v)}
        disabled={busy}
      >
        {showIssueForm ? '▲ I don\'t have a credential' : '▼ I don\'t have a credential'}
      </button>

      {showIssueForm && (
        <div style={{ background: 'var(--surface-2,#f0f4f8)', borderRadius: 8, padding: '1rem' }}>
          <p style={{ margin: '0 0 .75rem', fontSize: '.9rem', opacity: .75 }}>
            Provide your details below and a basic User credential will be issued to your KERI wallet.
          </p>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '.5rem' }}>
            <input
              className="paste-area"
              style={{ height: 'auto', padding: '.45rem .6rem', fontSize: '.95rem' }}
              placeholder="First name"
              value={issueForm.firstName}
              onChange={e => setIssueForm(f => ({ ...f, firstName: e.target.value }))}
              disabled={issueBusy}
            />
            <input
              className="paste-area"
              style={{ height: 'auto', padding: '.45rem .6rem', fontSize: '.95rem' }}
              placeholder="Last name"
              value={issueForm.lastName}
              onChange={e => setIssueForm(f => ({ ...f, lastName: e.target.value }))}
              disabled={issueBusy}
            />
            <input
              className="paste-area"
              style={{ height: 'auto', padding: '.45rem .6rem', fontSize: '.95rem' }}
              placeholder="Email address"
              type="email"
              value={issueForm.email}
              onChange={e => setIssueForm(f => ({ ...f, email: e.target.value }))}
              disabled={issueBusy}
            />
          </div>
          <button
            className="btn-primary"
            style={{ marginTop: '.75rem' }}
            onClick={issueCredential}
            disabled={issueBusy}
          >
            {issueBusy ? 'Issuing…' : 'Issue User Credential →'}
          </button>
        </div>
      )}

      {issueStatus && (
        <p className={`status ${issueStatus.type}`} style={{ marginTop: '.5rem' }}>
          {issueStatus.msg}
        </p>
      )}
    </div>
  )
}

function KycProofDisplay({ proof }: { proof: KycProof }) {
  const validUntilStr = proof.validUntilPosixMs
    ? `${new Date(proof.validUntilPosixMs).toISOString()} (${proof.validUntilPosixMs})`
    : '—'

  const fields: { label: string; value: string; mono?: boolean }[] = [
    { label: 'Payload (hex)', value: proof.payloadHex, mono: true },
    { label: 'Signature (hex)', value: proof.signatureHex, mono: true },
    { label: 'Entity VKey (hex)', value: proof.entityVkeyHex, mono: true },
    { label: 'TEL UTxO Reference', value: proof.entityTelUtxoRef, mono: true },
    { label: 'TEL Policy ID', value: proof.telPolicyId, mono: true },
    { label: 'Valid Until', value: validUntilStr },
    { label: 'Role', value: `${proof.roleName} (${proof.role})` },
  ]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '.75rem' }}>
      {fields.map(f => (
        <div key={f.label}>
          <span style={{ fontWeight: 600, fontSize: '.8rem', textTransform: 'uppercase', opacity: .55, display: 'block', marginBottom: '.25rem' }}>
            {f.label}
          </span>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.4rem' }}>
            <code style={{
              fontSize: '.82rem',
              wordBreak: 'break-all',
              flex: 1,
              ...(f.mono ? { fontFamily: 'var(--mono-font, monospace)' } : {}),
            }}>
              {f.value}
            </code>
            <CopyButton text={f.mono ? f.value : String(f.value)} />
          </div>
        </div>
      ))}
    </div>
  )
}

function StepDone({ credential, kycProof, onRestart }: {
  credential: CredentialData | null
  kycProof: KycProof | null
  onRestart: () => void
}) {
  return (
    <div className="card">
      <p className="card-title">KYC Proof</p>

      <div className="status ok" style={{ display: 'flex', alignItems: 'center', gap: '.5rem', marginBottom: '1.25rem' }}>
        <span style={{ fontWeight: 600 }}>✓ KYC proof generated</span>
      </div>

      <p className="card-subtitle" style={{ marginBottom: '1.25rem' }}>
        Use the values below when building Cardano transactions that require KYC verification.
        Include the TEL UTxO as a reference input and pass the payload + signature in the redeemer.
      </p>

      {/* KYC proof data */}
      {kycProof && (
        <div style={{ background: 'var(--surface-2,#f0f4f8)', borderRadius: 8, padding: '1rem', marginBottom: '1.25rem' }}>
          <KycProofDisplay proof={kycProof} />
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
