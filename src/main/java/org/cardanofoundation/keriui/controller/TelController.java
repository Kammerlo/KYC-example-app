package org.cardanofoundation.keriui.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.Role;
import org.cardanofoundation.keriui.domain.dto.TelInitResponse;
import org.cardanofoundation.keriui.domain.dto.TelMemberResponse;
import org.cardanofoundation.keriui.domain.entity.TelConfigEntity;
import org.cardanofoundation.keriui.service.TelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/tel")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Slf4j
public class TelController {

    private final TelService telService;

    /** Returns whether the TEL has been initialised and its on-chain addresses. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Optional<TelConfigEntity> cfg = telService.getConfig();
        if (cfg.isEmpty()) {
            return ResponseEntity.ok(Map.of("initialised", false));
        }
        TelConfigEntity c = cfg.get();
        return ResponseEntity.ok(Map.of(
                "initialised",   true,
                "scriptAddress", c.getScriptAddress(),
                "policyId",      c.getPolicyId(),
                "issuerVkey",    c.getIssuerVkey()
        ));
    }

    /** Build an unsigned TEL Init transaction. The frontend signs it with CIP-30 and submits. */
    @PostMapping("/build-init")
    public ResponseEntity<?> buildInit(@RequestBody BuildInitRequest body) {
        try {
            TelInitResponse result = telService.buildInitTx(body.issuerAddress());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("build-init failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Called after the TEL Init tx has been submitted. Stores the config in the database. */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest body) {
        try {
            TelConfigEntity config = telService.registerConfig(body.bootTxHash(), body.bootIndex());
            return ResponseEntity.ok(Map.of(
                    "scriptAddress", config.getScriptAddress(),
                    "policyId",      config.getPolicyId()
            ));
        } catch (Exception e) {
            log.error("register failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Assembles the wallet's witness set into the unsigned tx and submits it via Blockfrost. */
    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestBody SubmitRequest body) {
        try {
            String txHash = telService.assembleAndSubmit(body.unsignedTxCbor(), body.witnessCbor());
            return ResponseEntity.ok(Map.of("txHash", txHash));
        } catch (Exception e) {
            log.error("submit failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Returns all Trusted Entity List members with their role and UTxO reference. */
    @GetMapping("/members")
    public ResponseEntity<List<TelMemberResponse>> members() {
        List<TelMemberResponse> result = telService.getMembers().stream()
                .map(n -> {
                    int roleValue = n.getRole() != null ? n.getRole() : 2;
                    return new TelMemberResponse(
                            n.getVkey(),
                            telService.pkhFrom(n.getVkey()),
                            n.getTxHash(),
                            n.getOutputIndex(),
                            n.getNodePolicyId(),
                            roleValue,
                            Role.fromValue(roleValue).name());
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    /** Build an unsigned TEL Add transaction to add a new Trusted Entity. */
    @PostMapping("/build-add")
    public ResponseEntity<?> buildAdd(@RequestBody BuildAddRequest body) {
        try {
            String txCbor = telService.buildAddTx(body.entityVkeyHex(), body.role(), body.issuerAddress());
            return ResponseEntity.ok(Map.of("txCbor", txCbor));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("build-add failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Build an unsigned TEL Remove transaction to remove a Trusted Entity. */
    @PostMapping("/build-remove")
    public ResponseEntity<?> buildRemove(@RequestBody BuildRemoveRequest body) {
        try {
            String txCbor = telService.buildRemoveTx(body.entityVkeyHex(), body.issuerAddress());
            return ResponseEntity.ok(Map.of("txCbor", txCbor));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("build-remove failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Request bodies ────────────────────────────────────────────────────────

    record BuildInitRequest(String issuerAddress) {}
    record RegisterRequest(String bootTxHash, int bootIndex) {}
    /** @param role  0=User, 1=Institutional, 2=vLEI */
    record BuildAddRequest(String entityVkeyHex, int role, String issuerAddress) {}
    record BuildRemoveRequest(String entityVkeyHex, String issuerAddress) {}
    record SubmitRequest(String unsignedTxCbor, String witnessCbor) {}
}
