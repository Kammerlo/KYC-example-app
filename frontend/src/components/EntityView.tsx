export default function EntityView() {
  return (
    <div className="card">
      <div className="tel-status-row">
        <p className="card-title" style={{ margin: 0 }}>Trusted Entity</p>
        <span className="tel-badge tel-badge-ok">Verified</span>
      </div>
      <p className="card-subtitle" style={{ marginTop: '.5rem' }}>
        Your wallet is registered as a Trusted Entity in the TEL.
        KYC proofs are signed automatically when users complete verification.
      </p>
    </div>
  )
}
