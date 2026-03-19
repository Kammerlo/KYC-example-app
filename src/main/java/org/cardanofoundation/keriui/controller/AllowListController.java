package org.cardanofoundation.keriui.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.entity.AllowListConfigEntity;
import org.cardanofoundation.keriui.domain.entity.AllowListNodeEntity;
import org.cardanofoundation.keriui.service.AllowListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Optional<AllowListConfigEntity> cfg = allowListService.getConfig();
        if (cfg.isEmpty()) return ResponseEntity.ok(Map.of("initialised", false));
        AllowListConfigEntity c = cfg.get();
        return ResponseEntity.ok(Map.of(
                "initialised",   true,
                "scriptAddress", c.getScriptAddress(),
                "policyId",      c.getPolicyId()
        ));
    }

    @PostMapping("/build-init")
    public ResponseEntity<?> buildInit(@RequestBody BuildInitRequest body) {
        try {
            AllowListService.AllowListBuildResult r = allowListService.buildInitTx(body.entityAddress());
            return ResponseEntity.ok(Map.of(
                    "txCbor",        r.txCbor(),
                    "scriptAddress", r.scriptAddress(),
                    "policyId",      r.policyId(),
                    "bootTxHash",    r.bootTxHash(),
                    "bootIndex",     r.bootIndex()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("allowlist build-init failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest body) {
        try {
            AllowListConfigEntity c = allowListService.registerConfig(body.bootTxHash(), body.bootIndex());
            return ResponseEntity.ok(Map.of("scriptAddress", c.getScriptAddress(), "policyId", c.getPolicyId()));
        } catch (Exception e) {
            log.error("allowlist register failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

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

    @GetMapping("/members")
    public ResponseEntity<List<AllowListNodeEntity>> members() {
        return ResponseEntity.ok(allowListService.getMembers());
    }

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

    record BuildInitRequest(String entityAddress) {}
    record RegisterRequest(String bootTxHash, int bootIndex) {}
    record SetActiveRequest(String userPkh, boolean newActive, String entityAddress) {}
    record SubmitRequest(String unsignedTxCbor, String witnessCbor) {}
}