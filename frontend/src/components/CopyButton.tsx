import { useState } from 'react'

export default function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    await navigator.clipboard.writeText(text).catch(() => {})
    setCopied(true)
    setTimeout(() => setCopied(false), 1600)
  }
  return (
    <button className={`btn-copy${copied ? ' btn-copy--ok' : ''}`} onClick={copy} title={text}>
      {copied ? '✓' : 'copy'}
    </button>
  )
}