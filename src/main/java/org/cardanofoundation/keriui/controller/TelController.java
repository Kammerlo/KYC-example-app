package org.cardanofoundation.keriui.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Optional<TelConfigEntity> cfg = telService.getConfig();
        if (cfg.isEmpty()) {
            return ResponseEntity.ok(Map.of("initialised", false));
        }
        TelConfigEntity c = cfg.get();
        return ResponseEntity.ok(Map.of(
                "initialised", true,
                "scriptAddress", c.getScriptAddress(),
                "policyId", c.getPolicyId(),
                "issuerVkey", c.getIssuerVkey()
        ));
    }

    /** Build an unsigned TEL Init transaction. Frontend signs + submits via CIP-30. */
    @PostMapping("/build-init")
    public ResponseEntity<?> buildInit(@RequestBody BuildInitRequest body) {
        try {
            TelService.TelBuildResult result = telService.buildInitTx(body.issuerAddress());
            return ResponseEntity.ok(Map.of(
                    "txCbor",        result.txCbor(),
                    "scriptAddress", result.scriptAddress(),
                    "policyId",      result.policyId(),
                    "bootTxHash",    result.bootTxHash(),
                    "bootIndex",     result.bootIndex()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("build-init failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Called after frontend has successfully submitted the TEL Init tx. Saves config. */
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

    /** Assembles the wallet witness set into the unsigned tx and submits via blockfrost. */
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

    @GetMapping("/members")
    public ResponseEntity<List<Map<String, Object>>> members() {
        List<Map<String, Object>> result = telService.getMembers().stream()
                .map(n -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", n.getId());
                    m.put("vkey", n.getVkey());
                    m.put("nodePolicyId", n.getNodePolicyId());
                    m.put("txHash", n.getTxHash());
                    m.put("outputIndex", n.getOutputIndex());
                    m.put("pkh", telService.pkhFrom(n.getVkey()));
                    return m;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    /** Build an unsigned TEL Add transaction. Frontend signs + submits via CIP-30. */
    @PostMapping("/build-add")
    public ResponseEntity<?> buildAdd(@RequestBody BuildAddRequest body) {
        try {
            String txCbor = telService.buildAddTx(body.entityVkeyHex(), body.issuerAddress());
            return ResponseEntity.ok(Map.of("txCbor", txCbor));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("build-add failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Build an unsigned TEL Remove transaction. Frontend signs + submits via CIP-30. */
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

    record BuildInitRequest(String issuerAddress) {}
    record RegisterRequest(String bootTxHash, int bootIndex) {}
    record BuildAddRequest(String entityVkeyHex, String issuerAddress) {}
    record BuildRemoveRequest(String entityVkeyHex, String issuerAddress) {}
    record SubmitRequest(String unsignedTxCbor, String witnessCbor) {}
}
