import { apiGet } from './api'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
import { ensureSessionId } from './session'

type Step = 1 | 2 | 3 | 'done'

export function mountStepper(root: HTMLElement) {
  const sessionId = ensureSessionId()
  let currentStep: Step = 1
  let stopScan: (() => void) | null = null

  function goto(step: Step) {
    if (stopScan) { stopScan(); stopScan = null }
    currentStep = step
    render()
  }

  function render() {
    root.innerHTML = `
      <h1>KERI Connect</h1>
      <div class="stepper-header">
        <div class="step-pill ${currentStep === 1 ? 'active' : currentStep !== 1 ? 'done' : ''}" id="pill-1">
          ${currentStep !== 1 ? '✓' : '1'} Share your OOBI
        </div>
        <div class="step-connector"></div>
        <div class="step-pill ${currentStep === 2 ? 'active' : currentStep > 2 ? 'done' : ''}" id="pill-2">
          ${currentStep > 2 ? '✓' : '2'} Scan partner OOBI
        </div>
        <div class="step-connector"></div>
        <div class="step-pill ${currentStep === 3 ? 'active' : currentStep === 'done' ? 'done' : ''}" id="pill-3">
          ${currentStep === 'done' ? '✓' : '3'} Present credential
        </div>
      </div>
      <div id="step-body"></div>
    `
    const body = root.querySelector<HTMLDivElement>('#step-body')!
    if (currentStep === 1) renderStep1(body)
    else if (currentStep === 2) renderStep2(body)
    else if (currentStep === 3) renderStep3(body)
    else renderDone(body)
  }

  // ── Step 1: display own OOBI as QR + copy ──────────────────────────────
  function renderStep1(body: HTMLElement) {
    body.innerHTML = `
      <div class="card">
        <p class="card-title">Share your OOBI</p>
        <p class="card-subtitle">Let the other party scan or copy this OOBI to establish a connection.</p>
        <div id="s1-content"><p class="status info">Fetching OOBI…</p></div>
        <button class="btn-primary" id="s1-next" disabled>Next — Scan partner OOBI →</button>
      </div>
    `
    const content = body.querySelector<HTMLDivElement>('#s1-content')!
    const nextBtn = body.querySelector<HTMLButtonElement>('#s1-next')!

    fetchOwnOobi(sessionId).then(oobi => {
      content.innerHTML = `
        <div class="qr-wrapper"><canvas id="s1-qr"></canvas></div>
        <div class="copy-row">
          <input id="s1-text" readonly value="${oobi}" />
          <button class="btn-icon" id="s1-copy">Copy</button>
        </div>
      `
      QRCode.toCanvas(content.querySelector<HTMLCanvasElement>('#s1-qr')!, oobi, {
        width: 220, margin: 1,
        color: { dark: '#000000', light: '#ffffff' },
      })
      const copyBtn = content.querySelector<HTMLButtonElement>('#s1-copy')!
      copyBtn.addEventListener('click', async () => {
        await navigator.clipboard.writeText(oobi).catch(() => {})
        copyBtn.textContent = '✓ Copied'
        setTimeout(() => (copyBtn.textContent = 'Copy'), 1600)
      })
      nextBtn.disabled = false
    }).catch(err => {
      content.innerHTML = `<p class="status err">Failed to fetch OOBI: ${(err as Error).message}</p>`
    })

    nextBtn.addEventListener('click', () => goto(2))
  }

  // ── Step 2: scan partner OOBI (camera or paste) ────────────────────────
  function renderStep2(body: HTMLElement) {
    body.innerHTML = `
      <div class="card">
        <p class="card-title">Scan partner OOBI</p>
        <p class="card-subtitle">Point the camera at the partner's QR code, or paste the OOBI string below.</p>
        <div class="scanner-wrap" id="scanner-wrap">
          <video id="s2-video" autoplay playsinline muted></video>
          <canvas id="s2-canvas"></canvas>
          <div class="scan-overlay"><div class="scan-box"></div></div>
        </div>
        <p class="status info" id="cam-status">Starting camera…</p>
        <div class="divider">or paste manually</div>
        <textarea class="paste-area" id="s2-paste" placeholder="Paste OOBI string here…"></textarea>
        <button class="btn-primary" id="s2-submit" disabled>Resolve OOBI →</button>
        <p class="status" id="s2-status"></p>
      </div>
    `
    const video     = body.querySelector<HTMLVideoElement>('#s2-video')!
    const canvas    = body.querySelector<HTMLCanvasElement>('#s2-canvas')!
    const camSt     = body.querySelector<HTMLParagraphElement>('#cam-status')!
    const textarea  = body.querySelector<HTMLTextAreaElement>('#s2-paste')!
    const submitBtn = body.querySelector<HTMLButtonElement>('#s2-submit')!
    const statusEl  = body.querySelector<HTMLParagraphElement>('#s2-status')!

    let scanning = true
    let stream: MediaStream | null = null

    textarea.addEventListener('input', () => {
      submitBtn.disabled = textarea.value.trim().length === 0
    })

    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
      .then(s => {
        stream = s
        video.srcObject = s
        video.play()
        camSt.textContent = 'Scanning…'
        requestAnimationFrame(scanFrame)
      })
      .catch(() => {
        camSt.textContent = 'Camera unavailable — use paste instead.'
        camSt.className = 'status err'
        body.querySelector<HTMLDivElement>('#scanner-wrap')!.style.display = 'none'
      })

    function scanFrame() {
      if (!scanning) return
      if (video.readyState === video.HAVE_ENOUGH_DATA) {
        canvas.width = video.videoWidth
        canvas.height = video.videoHeight
        const ctx = canvas.getContext('2d')!
        ctx.drawImage(video, 0, 0)
        const img = ctx.getImageData(0, 0, canvas.width, canvas.height)
        const result = jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' })
        if (result?.data) {
          scanning = false
          stopCamera(stream)
          camSt.textContent = '✓ QR detected'
          camSt.className = 'status ok'
          textarea.value = result.data
          submitBtn.disabled = false
          setTimeout(() => doResolve(result.data, statusEl, submitBtn), 600)
          return
        }
      }
      requestAnimationFrame(scanFrame)
    }

    submitBtn.addEventListener('click', () => {
      const val = textarea.value.trim()
      if (val) doResolve(val, statusEl, submitBtn)
    })

    stopScan = () => { scanning = false; stopCamera(stream) }
  }

  async function doResolve(oobi: string, statusEl: HTMLElement, btn: HTMLButtonElement) {
    btn.disabled = true
    setStatus(statusEl, 'info', 'Resolving OOBI…')
    try {
      const res = await apiGet(`/api/keri/oobi/resolve?oobi=${encodeURIComponent(oobi)}`, {
        headers: { 'X-Session-Id': ensureSessionId() },
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setStatus(statusEl, 'ok', 'Connection established!')
      // after successful resolve, proceed to present credential step
      setTimeout(() => goto(3), 800)
    } catch (e) {
      setStatus(statusEl, 'err', `Failed: ${e instanceof Error ? e.message : String(e)}`)
      btn.disabled = false
    }
  }

  // ── Step 3: request user to present credential via wallet ─────────────────
  function renderStep3(body: HTMLElement) {
    body.innerHTML = `
      <div class="card">
        <p class="card-title">Present KYC Credential</p>
        <p class="card-subtitle">We will request the other party to present their KYC credential. When prompted, the user must accept/approve the presentation in their wallet.</p>
        <div id="s3-content">
          <p class="status info">Ready to request credential presentation.</p>
        </div>
        <button class="btn-primary" id="s3-present">Request Presentation →</button>
      </div>
    `
    const content = body.querySelector<HTMLDivElement>('#s3-content')!
    const btn = body.querySelector<HTMLButtonElement>('#s3-present')!

    btn.addEventListener('click', async () => {
      btn.disabled = true
      setStatus(content, 'info', 'Requesting presentation — please present the credential in your wallet when prompted...')
      try {
        const res = await apiGet('/api/keri/credential/present', { headers: { 'X-Session-Id': ensureSessionId() } })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        setStatus(content, 'ok', 'Presentation completed successfully.')
        setTimeout(() => goto('done'), 900)
      } catch (err) {
        setStatus(content, 'err', `Presentation failed: ${err instanceof Error ? err.message : String(err)}`)
        btn.disabled = false
      }
    })
  }

  // ── Done ───────────────────────────────────────────────────────────────
  function renderDone(body: HTMLElement) {
    body.innerHTML = `
      <div class="card">
        <div class="success-icon">🎉</div>
        <p class="card-title" style="text-align:center">Connection established</p>
        <p class="card-subtitle" style="text-align:center;margin-top:.5rem">
          Both OOBIs have been exchanged and resolved successfully.
        </p>
        <button class="btn-primary" id="restart-btn">Start over</button>
      </div>
    `
    body.querySelector('#restart-btn')!.addEventListener('click', () => goto(1))
  }

  render()
}

async function fetchOwnOobi(sessionId: string): Promise<string> {
  const res = await apiGet('/api/keri/oobi', { headers: { 'X-Session-Id': sessionId } })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const data = await res.json()
  if (!data.oobi) throw new Error('No oobi in response')
  return data.oobi as string
}

function stopCamera(stream: MediaStream | null) {
  stream?.getTracks().forEach(t => t.stop())
}

function setStatus(el: HTMLElement, type: 'info' | 'ok' | 'err', msg: string) {
  el.className = `status ${type}`
  // element may be a container so set textContent accordingly
  if (el instanceof HTMLParagraphElement) el.textContent = msg
  else el.textContent = msg
}
