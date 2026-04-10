// CIP-30 wallet integration

export type ConnectedWallet = {
  name: string
  icon?: string
  api: CIP30Api
  changeAddress: string  // bech32
}

type CIP30Api = {
  getChangeAddress(): Promise<string>  // cbor hex of address
  getUsedAddresses(paginate?: unknown): Promise<string[]>
  signTx(tx: string, partialSign?: boolean): Promise<string>
  signData(address: string, payload: string): Promise<{ signature: string; key: string }>
  submitTx(tx: string): Promise<string>
  getUtxos(amount?: string, paginate?: unknown): Promise<string[] | null>
}

/** Return available CIP-30 providers (injected by browser wallet extensions). */
export function getAvailableWallets(): { key: string; name: string; icon?: string }[] {
  // @ts-ignore
  const cardano = window.cardano as Record<string, any> | undefined
  if (!cardano) return []
  return Object.keys(cardano)
    .filter(k => cardano[k] && typeof cardano[k].enable === 'function')
    .map(k => ({ key: k, name: cardano[k].name ?? k, icon: cardano[k].icon }))
}

/** Connect to a specific CIP-30 wallet by its key. */
export async function connectWalletByKey(key: string): Promise<ConnectedWallet> {
  // @ts-ignore
  const cardano = window.cardano as Record<string, any>
  if (!cardano?.[key]) throw new Error(`Wallet '${key}' not found`)
  const api: CIP30Api = await cardano[key].enable()
  const changeAddressCbor = await api.getChangeAddress()
  const changeAddress = decodeCborAddress(changeAddressCbor)
  return { name: cardano[key].name ?? key, icon: cardano[key].icon, api, changeAddress }
}

/**
 * Sign an unsigned transaction (CBOR hex) and return the fully signed tx.
 * Uses CIP-30 signTx with partialSign=false so the wallet returns the complete signed tx.
 */
export async function signTx(wallet: ConnectedWallet, unsignedTxHex: string): Promise<string> {
  return wallet.api.signTx(unsignedTxHex, true)
}

/** Submit a signed transaction via the wallet's own submission channel. */
export async function submitTx(wallet: ConnectedWallet, signedTxHex: string): Promise<string> {
  return wallet.api.submitTx(signedTxHex)
}

/**
 * Returns the best address to use as a fee payer / UTxO source.
 * Prefers a used address (which is known to have had transactions) over the
 * change address (which may be a fresh derivation that Blockfrost doesn't know about).
 * Falls back to the change address if no used addresses exist.
 */
export async function getFundedAddress(wallet: ConnectedWallet): Promise<string> {
  try {
    const used = await wallet.api.getUsedAddresses()
    if (used && used.length > 0) {
      return decodeCborAddress(used[0])
    }
  } catch {
    // Some wallets may not support getUsedAddresses — fall through
  }
  return wallet.changeAddress
}

/**
 * Decode a CBOR-encoded address to bech32.
 * CIP-30 returns addresses as CBOR-encoded byte strings (e.g. 5839...).
 * We strip the CBOR header bytes and convert the raw address bytes to bech32.
 */
function decodeCborAddress(cborHex: string): string {
  // CBOR bytestring: starts with 0x58 (1-byte length) or 0x59 (2-byte length)
  // followed by the raw address bytes.
  const bytes = hexToBytes(cborHex)
  let offset = 0
  const first = bytes[offset++]
  if (first === 0x58) {
    offset++ // skip 1-byte length
  } else if (first === 0x59) {
    offset += 2 // skip 2-byte length
  } else if (first >= 0x40 && first <= 0x57) {
    // short bytestring, length encoded in lower nibble — no extra length bytes
  } else {
    // Already bech32 (some wallets skip CBOR encoding)
    if (cborHex.startsWith('addr') || cborHex.startsWith('stake')) {
      return cborHex
    }
    // Raw address bytes with no CBOR wrapper
    return addressBytesToBech32(bytes)
  }
  const addrBytes = bytes.slice(offset)
  return addressBytesToBech32(addrBytes)
}

function addressBytesToBech32(bytes: Uint8Array): string {
  // Determine prefix from header nibble
  const header = bytes[0]
  const networkId = header & 0x0f
  const isTestnet = networkId === 0
  // Address type from upper nibble
  const addrType = (header >> 4) & 0x0f
  // Enterprise (type 6) or Base (type 0/1) or pointer (type 4/5)
  let prefix: string
  if (addrType >= 6 && addrType <= 7) {
    prefix = isTestnet ? 'addr_test' : 'addr'
  } else {
    prefix = isTestnet ? 'addr_test' : 'addr'
  }
  return bech32Encode(prefix, bytes)
}

// ── Minimal bech32 encoder ────────────────────────────────────────────────────

const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l'
const GENERATOR = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]

function bech32Polymod(values: number[]): number {
  let chk = 1
  for (const v of values) {
    const top = chk >> 25
    chk = ((chk & 0x1ffffff) << 5) ^ v
    for (let i = 0; i < 5; i++) {
      if ((top >> i) & 1) chk ^= GENERATOR[i]
    }
  }
  return chk
}

function bech32HrpExpand(hrp: string): number[] {
  const ret: number[] = []
  for (let i = 0; i < hrp.length; i++) ret.push(hrp.charCodeAt(i) >> 5)
  ret.push(0)
  for (let i = 0; i < hrp.length; i++) ret.push(hrp.charCodeAt(i) & 31)
  return ret
}

function convertBits(data: Uint8Array, from: number, to: number, pad: boolean): number[] {
  let acc = 0, bits = 0
  const ret: number[] = []
  const maxv = (1 << to) - 1
  for (const value of data) {
    acc = (acc << from) | value
    bits += from
    while (bits >= to) {
      bits -= to
      ret.push((acc >> bits) & maxv)
    }
  }
  if (pad && bits > 0) ret.push((acc << (to - bits)) & maxv)
  return ret
}

function bech32Encode(hrp: string, bytes: Uint8Array): string {
  const data = convertBits(bytes, 8, 5, true)
  const enc = [...bech32HrpExpand(hrp), ...data]
  const chk = bech32Polymod([...enc, 0, 0, 0, 0, 0, 0]) ^ 1
  let result = hrp + '1'
  for (const d of data) result += CHARSET[d]
  for (let i = 0; i < 6; i++) result += CHARSET[(chk >> (5 * (5 - i))) & 31]
  return result
}


function hexToBytes(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2)
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  }
  return bytes
}
