package org.cardanofoundation.keriui.service;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AuthService {

    private record ChallengeEntry(String challengeHex, Instant issuedAt) {}

    private final Map<String, ChallengeEntry> challenges = new ConcurrentHashMap<>();
    private static final long TTL_SECONDS = 300;

    /**
     * Generate a signing challenge tied to the session ID.
     * Challenge bytes = UTF-8 encoding of "KERI:LOGIN:<sessionId>:<epochSeconds>"
     * returned as hex so the frontend can pass directly to CIP-30 signData.
     */
    public String generateChallenge(String sessionId) {
        String text = "KERI:LOGIN:" + sessionId + ":" + Instant.now().getEpochSecond();
        String challengeHex = HexUtil.encodeHexString(text.getBytes(StandardCharsets.UTF_8));
        challenges.put(sessionId, new ChallengeEntry(challengeHex, Instant.now()));
        log.debug("Challenge issued for session {}", sessionId);
        return challengeHex;
    }

    /**
     * Verify a CIP-30 signData response.
     * CIP-30 signData wraps the payload in a CIP-8 Sig_Structure and returns:
     *   - signature: CBOR(COSE_Sign1) hex
     *   - key:       CBOR(COSE_Key)  hex
     *
     * Verifies:
     *  1. Challenge exists and has not expired
     *  2. The raw vkey is extractable from COSE_Key
     *  3. blake2b_224(vkey) == PKH derived from address
     *  4. The COSE_Sign1 payload (Bstr wrapped in Sig_Structure) contains the challenge
     *  5. The Ed25519 signature is valid over the Sig_Structure
     *
     * Returns the wallet's payment key hash (hex) on success, throws otherwise.
     */
    public String verifyAndGetPkh(String sessionId, String address,
                                   String signatureCbor, String keyCbor) {
        ChallengeEntry entry = challenges.get(sessionId);
        if (entry == null) {
            throw new IllegalArgumentException("No pending challenge for this session");
        }
        if (Instant.now().isAfter(entry.issuedAt().plusSeconds(TTL_SECONDS))) {
            challenges.remove(sessionId);
            throw new IllegalArgumentException("Challenge has expired");
        }

        try {
            // ── 1. Extract raw vkey from COSE_Key ──────────────────────────────
            String keyHex = keyCbor.toLowerCase();
            String marker = "215820"; // CBOR: map key -2 (0x21) + bytes(32) tag (0x5820)
            int idx = keyHex.indexOf(marker);
            if (idx == -1) throw new IllegalArgumentException("Cannot extract vkey from COSE_Key");
            String vkeyHex = keyHex.substring(idx + marker.length(), idx + marker.length() + 64);
            if (vkeyHex.length() != 64)
                throw new IllegalArgumentException("Extracted vkey has wrong length");

            // ── 2. Verify address PKH matches the vkey ─────────────────────────
            Address addr = new Address(address);
            byte[] pkh = addr.getPaymentCredentialHash()
                    .orElseThrow(() -> new IllegalArgumentException("Cannot extract PKH from address"));
            String walletPkh = HexUtil.encodeHexString(pkh);
            String derivedPkh = HexUtil.encodeHexString(
                    Blake2bUtil.blake2bHash224(HexUtil.decodeHexString(vkeyHex)));
            if (!walletPkh.equalsIgnoreCase(derivedPkh)) {
                throw new IllegalArgumentException("Address does not match the signing key");
            }

            // ── 3. Decode COSE_Sign1 ───────────────────────────────────────────
            // COSE_Sign1 = [protected_header: bstr, unprotected: map, payload: bstr, signature: bstr]
            byte[] sigBytes = HexUtil.decodeHexString(signatureCbor);
            List<DataItem> items = CborDecoder.decode(sigBytes);
            Array coseSign1 = (Array) items.getFirst();
            List<DataItem> elems = coseSign1.getDataItems();

            byte[] protectedHeader = ((ByteString) elems.get(0)).getBytes();
            byte[] payload         = ((ByteString) elems.get(2)).getBytes();
            byte[] signature       = ((ByteString) elems.get(3)).getBytes();

            // ── 4. Verify payload contains the challenge ───────────────────────
            // CIP-8 wraps our raw bytes in a map but the actual content is there
            String payloadHex = HexUtil.encodeHexString(payload).toLowerCase();
            if (!payloadHex.contains(entry.challengeHex().toLowerCase())) {
                throw new IllegalArgumentException("Signed payload does not contain the expected challenge");
            }

            // ── 5. Verify Ed25519 over the Sig_Structure ──────────────────────
            // Sig_Structure = ["Signature1", protected_header, external_aad="", payload]
            Array sigStruct = new Array();
            sigStruct.add(new UnicodeString("Signature1"));
            sigStruct.add(new ByteString(protectedHeader));
            sigStruct.add(new ByteString(new byte[0]));
            sigStruct.add(new ByteString(payload));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(sigStruct);
            byte[] toVerify = baos.toByteArray();

            Ed25519PublicKeyParameters pubKey =
                    new Ed25519PublicKeyParameters(HexUtil.decodeHexString(vkeyHex), 0);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, pubKey);
            signer.update(toVerify, 0, toVerify.length);
            if (!signer.verifySignature(signature)) {
                throw new IllegalArgumentException("Ed25519 signature is invalid");
            }

            challenges.remove(sessionId);
            log.info("Auth verified for session {}, pkh={}", sessionId, walletPkh);
            return walletPkh;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Auth verification error", e);
            throw new IllegalArgumentException("Verification failed: " + e.getMessage());
        }
    }
}