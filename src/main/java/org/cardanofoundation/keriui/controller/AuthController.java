package org.cardanofoundation.keriui.controller;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.dto.RoleDetectionResponse;
import org.cardanofoundation.keriui.domain.dto.RoleInfo;
import org.cardanofoundation.keriui.service.AuthService;
import org.cardanofoundation.keriui.service.TelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final TelService telService;
    private final AuthService authService;

    /**
     * Detect the role of a connected Cardano wallet.
     * The frontend sends the wallet's change address (bech32); the backend
     * extracts the payment key hash and compares it against:
     *   <ul>
     *     <li>the configured issuer vkey  → role="issuer", teRole=2 (vLEI)</li>
     *     <li>any vkey stored in the TEL  → role="entity", teRole=their registered role</li>
     *     <li>otherwise                   → role="user", no teRole</li>
     *   </ul>
     */
    @PostMapping("/role")
    public ResponseEntity<?> detectRole(@RequestBody RoleRequest body) {
        try {
            Address addr;
            try {
                addr = new Address(body.address());
            } catch (RuntimeException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid address: " + e.getMessage()));
            }
            byte[] pkh = addr.getPaymentCredentialHash()
                    .orElseThrow(() -> new IllegalArgumentException("Cannot extract payment key hash from address"));
            String pkhHex = HexUtil.encodeHexString(pkh);
            log.info("detectRole: address={} pkh={}", body.address(), pkhHex);

            RoleInfo roleInfo = telService.roleInfoForPkh(pkhHex);
            Integer teRole     = roleInfo.teRole() != null ? roleInfo.teRole().getValue() : null;
            String teRoleName  = roleInfo.teRole() != null ? roleInfo.teRole().name()     : null;

            return ResponseEntity.ok(new RoleDetectionResponse(roleInfo.roleName(), pkhHex, teRole, teRoleName));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("role detection failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Issue a signing challenge tied to the given session ID.
     * Returns the challenge as a hex string to be passed to CIP-30 signData.
     */
    @GetMapping("/challenge")
    public ResponseEntity<?> challenge(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        }
        String challengeHex = authService.generateChallenge(sessionId);
        return ResponseEntity.ok(Map.of("challenge", challengeHex));
    }

    /**
     * Verify a CIP-30 signData response against the stored challenge.
     * Returns the wallet's payment key hash on success.
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest body) {
        try {
            String pkh = authService.verifyAndGetPkh(
                    body.sessionId(), body.address(), body.signature(), body.key());
            return ResponseEntity.ok(Map.of("pkh", pkh, "verified", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("verify failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Request bodies ────────────────────────────────────────────────────────

    record RoleRequest(String address) {}
    record VerifyRequest(String sessionId, String address, String signature, String key) {}
}
