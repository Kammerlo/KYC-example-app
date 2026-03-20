package org.cardanofoundation.keriui.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.Role;
import org.cardanofoundation.keriui.domain.dto.WlInitResponse;
import org.cardanofoundation.keriui.domain.dto.WlMemberResponse;
import org.cardanofoundation.keriui.domain.entity.AllowListConfigEntity;
import org.cardanofoundation.keriui.service.AllowListService;
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
@RequestMapping("/api/allowlist")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Slf4j
public class AllowListController {

    private final AllowListService allowListService;

    /** Returns whether the AllowList has been initialised and its on-chain addresses. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Optional<AllowListConfigEntity> cfg = allowListService.getConfig();
        if (cfg.isEmpty()) {
            return ResponseEntity.ok(Map.of("initialised", false));
        }
        AllowListConfigEntity c = cfg.get();
        return ResponseEntity.ok(Map.of(
                "initialised",   true,
                "scriptAddress", c.getScriptAddress(),
                "policyId",      c.getPolicyId()
        ));
    }

    /** Build an unsigned AllowList Init transaction. The frontend signs it with CIP-30 and submits. */
    @PostMapping("/build-init")
    public ResponseEntity<?> buildInit(@RequestBody BuildInitRequest body) {
        try {
            WlInitResponse result = allowListService.buildInitTx(body.entityAddress());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("allowlist build-init failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Called after the AllowList Init tx has been submitted. Stores the config in the database. */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest body) {
        try {
            AllowListConfigEntity c = allowListService.registerConfig(body.bootTxHash(), body.bootIndex());
            return ResponseEntity.ok(Map.of(
                    "scriptAddress", c.getScriptAddress(),
                    "policyId",      c.getPolicyId()
            ));
        } catch (Exception e) {
            log.error("allowlist register failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Assembles the wallet's witness set into the unsigned tx and submits it via Blockfrost. */
    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestBody SubmitRequest body) {
        try {
            String txHash = allowListService.assembleAndSubmit(body.unsignedTxCbor(), body.witnessCbor());
            return ResponseEntity.ok(Map.of("txHash", txHash));
        } catch (Exception e) {
            log.error("allowlist submit failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Returns all AllowList members (excludes the head sentinel) with their role and UTxO reference. */
    @GetMapping("/members")
    public ResponseEntity<List<WlMemberResponse>> members() {
        List<WlMemberResponse> result = allowListService.getMembers().stream()
                .map(n -> {
                    int roleValue = n.getRole() != null ? n.getRole() : 0;
                    return new WlMemberResponse(
                            n.getPkh(),
                            Boolean.TRUE.equals(n.getActive()),
                            n.getNodePolicyId(),
                            n.getTxHash(),
                            n.getOutputIndex(),
                            roleValue,
                            Role.fromValue(roleValue).name());
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    /** Build an unsigned SetActive transaction to activate or deactivate an AllowList member. */
    @PostMapping("/build-set-active")
    public ResponseEntity<?> buildSetActive(@RequestBody SetActiveRequest body) {
        try {
            String txCbor = allowListService.buildSetActiveTx(body.userPkh(), body.newActive(), body.entityAddress());
            return ResponseEntity.ok(Map.of("txCbor", txCbor));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("allowlist build-set-active failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Request bodies ────────────────────────────────────────────────────────

    record BuildInitRequest(String entityAddress) {}
    record RegisterRequest(String bootTxHash, int bootIndex) {}
    record SetActiveRequest(String userPkh, boolean newActive, String entityAddress) {}
    record SubmitRequest(String unsignedTxCbor, String witnessCbor) {}
}
