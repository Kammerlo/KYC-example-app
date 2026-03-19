import { useState, useEffect, useRef, useCallback } from 'react'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
import { apiGet, apiPost } from '../api'
import { getAvailableWallets, connectWalletByKey, type ConnectedWallet } from '../wallet'

type CredentialData = { email: string; firstName: string; lastName: string }
type Step = 1 | 2 | 3 | 4 | 'done'

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
  const stopScanRef = useRef<(() => void) | null>(null)

  // On mount: check whether this session already has KYC data + Cardano address stored
  useEffect(() => {
    const sessionId = getSessionId()
    apiGet('/api/keri/session', { headers: { 'X-Session-Id': sessionId } })
      .then(r => r.json())
      .then((data: {
        exists: boolean; hasCredential: boolean; hasCardanoAddress: boolean;
        firstName?: string; lastName?: string; email?: string; cardanoAddress?: string
      }) => {
        if (data.hasCredential) {
          setCredential({
            firstName: data.firstName ?? '',
            lastName: data.lastName ?? '',
            email: data.email ?? '',
          })
          setStep(4)
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
        {step === 'done' && <StepDone onRestart={() => { setCredential(null); setStoredAddress(null); goto(1) }} />}
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

function Step3({ onNext }: { onNext: (data: CredentialData) => void }) {
  const [status, setStatus] = useState<{ type: string; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const sessionId = getSessionId()

  async function present() {
    setBusy(true)
    setStatus({ type: 'info', msg: 'Requesting presentation — please approve in your wallet when prompted…' })
    try {
      const res = await apiGet('/api/keri/credential/present', { headers: { 'X-Session-Id': sessionId } })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data: CredentialData = await res.json()
      setStatus({ type: 'ok', msg: 'Presentation completed successfully.' })
      setTimeout(() => onNext(data), 900)
    } catch (e) {
      setStatus({ type: 'err', msg: `Failed: ${e instanceof Error ? e.message : String(e)}` })
      setBusy(false)
    }
  }

  return (
    <div className="card">
      <p className="card-title">Present KYC Credential</p>
      <p className="card-subtitle">
        We will request the other party to present their KYC credential.
        When prompted, approve the presentation in your wallet.
      </p>
      {status
        ? <p className={`status ${status.type}`}>{status.msg}</p>
        : <p className="status info">Ready to request credential presentation.</p>}
      <button className="btn-primary" onClick={present} disabled={busy} style={{ marginTop: '.75rem' }}>
        Request Presentation →
      </button>
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
  const [txCbor, setTxCbor] = useState<string | null>(null)
  const [buildStatus, setBuildStatus] = useState<{ type: string; msg: string } | null>(null)
  const [submitStatus, setSubmitStatus] = useState<{ type: string; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)

  const availableWallets = getAvailableWallets()

  async function connectWallet(key: string) {
    setWalletErr(null)
    try {
      const w = await connectWalletByKey(key)
      setWallet(w)
      // Persist address (creates or overwrites)
      await apiPost('/api/keri/session/cardano-address',
        { cardanoAddress: w.changeAddress },
        { headers: { 'X-Session-Id': sessionId } }
      )
      onAddressStored(w.changeAddress)
    } catch (e) {
      setWalletErr(`Failed to connect: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  async function buildTx() {
    setBusy(true)
    setBuildStatus({ type: 'info', msg: 'Building transaction…' })
    setSubmitStatus(null)
    try {
      const res = await fetch('/api/keri/allowlist/build-add-tx', {
        method: 'POST',
        headers: { 'X-Session-Id': sessionId },
      })
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: `HTTP ${res.status}` }))
        throw new Error(err.error ?? `HTTP ${res.status}`)
      }
      const data: { txCbor: string } = await res.json()
      setTxCbor(data.txCbor)
      setBuildStatus({ type: 'ok', msg: 'Transaction built — sign it with your wallet below.' })
    } catch (e) {
      setBuildStatus({ type: 'err', msg: `${e instanceof Error ? e.message : String(e)}` })
    } finally {
      setBusy(false)
    }
  }

  async function signAndSubmit() {
    if (!wallet || !txCbor) return
    setBusy(true)
    setSubmitStatus({ type: 'info', msg: 'Waiting for wallet signature…' })
    try {
      const witnessCbor = await wallet.api.signTx(txCbor, true)
      setSubmitStatus({ type: 'info', msg: 'Submitting transaction…' })
      const res = await apiPost('/api/allowlist/submit', { unsignedTxCbor: txCbor, witnessCbor })
      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: `HTTP ${res.status}` }))
        throw new Error(err.error ?? `HTTP ${res.status}`)
      }
      const data: { txHash: string } = await res.json()
      setSubmitStatus({ type: 'ok', msg: `Submitted! Tx: ${data.txHash.slice(0, 16)}…` })
      setTimeout(onNext, 1200)
    } catch (e) {
      setSubmitStatus({ type: 'err', msg: `${e instanceof Error ? e.message : String(e)}` })
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
          <span style={{ fontWeight: 600, fontSize: '.8rem', textTransform: 'uppercase', opacity: .55 }}>
            KYC Credential
          </span>
          <button
            className="btn-icon"
            style={{ fontSize: '.8rem' }}
            onClick={onUpdateCredentials}
          >
            Update credentials
          </button>
        </div>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '.95rem' }}>
          <tbody>
            <tr>
              <td style={{ padding: '3px 0', opacity: .65, width: '36%' }}>First name</td>
              <td style={{ padding: '3px 0', fontWeight: 500 }}>{credential.firstName || '—'}</td>
            </tr>
            <tr>
              <td style={{ padding: '3px 0', opacity: .65 }}>Last name</td>
              <td style={{ padding: '3px 0', fontWeight: 500 }}>{credential.lastName || '—'}</td>
            </tr>
            <tr>
              <td style={{ padding: '3px 0', opacity: .65 }}>Email</td>
              <td style={{ padding: '3px 0', fontWeight: 500 }}>{credential.email || '—'}</td>
            </tr>
          </tbody>
        </table>
      </div>

      {/* Cardano wallet section */}
      {!wallet ? (
        <div>
          {storedAddress && (
            <p className="status info" style={{ marginBottom: '.75rem' }}>
              Registered address: <code style={{ fontSize: '.8rem' }}>{storedAddress.slice(0, 24)}…</code>
              <br />Connect your wallet to sign the transaction.
            </p>
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
              <button
                key={w.key}
                className="btn-primary"
                style={{ display: 'flex', alignItems: 'center', gap: '.4rem' }}
                onClick={() => connectWallet(w.key)}
              >
                {w.icon && <img src={w.icon} alt="" style={{ width: 20, height: 20 }} />}
                {w.name}
              </button>
            ))}
          </div>
          {walletErr && <p className="status err" style={{ marginTop: '.5rem' }}>{walletErr}</p>}
        </div>
      ) : (
        <div>
          <p className="status ok" style={{ marginBottom: '1rem' }}>
            ✓ {wallet.name} connected — {activeAddress?.slice(0, 22)}…
          </p>

          {!txCbor ? (
            <button className="btn-primary" onClick={buildTx} disabled={busy}>
              Build Allow List Transaction →
            </button>
          ) : (
            <button className="btn-primary" onClick={signAndSubmit} disabled={busy}>
              Sign &amp; Submit Transaction →
            </button>
          )}

          {buildStatus && (
            <p className={`status ${buildStatus.type}`} style={{ marginTop: '.75rem' }}>
              {buildStatus.msg}
            </p>
          )}
          {submitStatus && (
            <p className={`status ${submitStatus.type}`} style={{ marginTop: '.5rem' }}>
              {submitStatus.msg}
            </p>
          )}
        </div>
      )}
    </div>
  )
}

function StepDone({ onRestart }: { onRestart: () => void }) {
  return (
    <div className="card">
      <div className="success-icon">🎉</div>
      <p className="card-title" style={{ textAlign: 'center' }}>You are on the Allow List!</p>
      <p className="card-subtitle" style={{ textAlign: 'center', marginTop: '.5rem' }}>
        Your KYC credential has been verified and you have been added to the Allow List.
      </p>
      <button className="btn-primary" onClick={onRestart} style={{ marginTop: '1rem' }}>
        Start over
      </button>
    </div>
  )
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    await navigator.clipboard.writeText(text).catch(() => {})
    setCopied(true); setTimeout(() => setCopied(false), 1600)
  }
  return <button className="btn-icon" onClick={copy}>{copied ? '✓ Copied' : 'Copy'}</button>
}