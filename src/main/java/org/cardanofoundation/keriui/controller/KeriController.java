package org.cardanofoundation.keriui.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.IdentifierConfig;
import org.cardanofoundation.keriui.domain.entity.KYCEntity;
import org.cardanofoundation.keriui.domain.repository.KycRepository;
import org.cardanofoundation.keriui.domain.responses.GetOobiResponse;
import org.cardanofoundation.keriui.service.AllowListService;
import org.cardanofoundation.keriui.util.IpexNotificationHelper;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAgreeArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexApplyArgs;
import org.cardanofoundation.signify.core.States;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.security.DigestException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/keri")
@CrossOrigin(origins = "http://localhost:5173")
@RequiredArgsConstructor
@Slf4j
public class KeriController {

    private final IdentifierConfig identifierConfig;
    private final SignifyClient client;
    private final KycRepository kycRepository;
    private final AllowListService allowListService;
    private static final Pattern OOBI_AID_PATTERN = Pattern.compile("/oobi/([^/]+)");
    @Value("${keri.identifier.name}")
    private String identifierName;
    @Value("${keri.schema.said}")
    private String schemaSaid;
    @Value("${keri.schema.base-url}")
    private String schemaBaseUrl;
    @Value("${keri.signing-mnemonic}")
    private String signingMnemonic;

    private static final DateTimeFormatter KERI_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'");


    @GetMapping("/oobi")
    public ResponseEntity<GetOobiResponse> getOobi(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) throws IOException, InterruptedException {
        Optional<Object> o = client.oobis().get(identifierConfig.getName(), null);
        if (!o.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> stringObjectMap = (Map<String, Object>) o.get();
        List<String> oobis = (List<String>) stringObjectMap.get("oobis");
        if(oobis.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(GetOobiResponse.builder()
                .oobi(oobis.getFirst())
                .build()
        );
    }

    @GetMapping("/oobi/resolve")
    public ResponseEntity<Boolean> resolveOobi(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam String oobi) throws IOException, InterruptedException {
        Object resolve = client.oobis().resolve(oobi, sessionId);
        Operation<Object> wait = client.operations().wait(Operation.fromObject(resolve));

        if(wait.isDone()) {
            Matcher matcher = OOBI_AID_PATTERN.matcher(URI.create(oobi).getPath());
            if (!matcher.find()) {
                throw new IllegalArgumentException("No AID found in OOBI URL: " + oobi);
            }
            String aid = matcher.group(1);
            Optional<Object> o = client.contacts().get(aid);
            KYCEntity kycEntity = KYCEntity.builder()
                    .sessionId(sessionId)
                    .oobi(oobi)
                    .aid(aid)
                    .build();
            kycRepository.save(kycEntity);
            return ResponseEntity.ok(true);
        } else {
            return ResponseEntity.internalServerError().body(false);
        }
    }

    @GetMapping("/credential/present")
    public ResponseEntity<?> presentCredential(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) throws Exception {
        Optional<KYCEntity> kycEntity = kycRepository.findById(sessionId);
        if(!kycEntity.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        String aid = kycEntity.get().getAid();
        IpexApplyArgs args = IpexApplyArgs.builder()
                .recipient(aid)
                .senderName(identifierName)
                .schemaSaid(schemaSaid)
                .attributes(Map.of("oobiUrl", schemaBaseUrl))
                .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                .build();
        Exchanging.ExchangeMessageResult exchangeMessageResult = client.ipex().apply(args);
        Object o = client.ipex().submitApply(identifierName, exchangeMessageResult.exn(), exchangeMessageResult.sigs(), Collections.singletonList(aid));
        Operation<Object> wait = client.operations().wait(Operation.fromObject(o));
        // ── STEP 2: Wait for /ipex/offer from the wallet ──────────────────────
        // The Veridian wallet user will see the request and tap "Share" — this
        // triggers an offer message back to you. Poll until it arrives.
        System.out.println("⏳ Waiting for wallet to respond with an offer...");
        IpexNotificationHelper.Notification offerNote =
                IpexNotificationHelper.waitForNotification(client, "/exn/ipex/offer");

        // Fetch the full offer exchange message to extract the offer SAID
        Object offerExchange = client.exchanges().get(offerNote.a.d).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> offerExn = (Map<String, Object>)
                ((LinkedHashMap<String, Object>) offerExchange).get("exn");

        String offerSaid = offerExn.get("d").toString();
        String offerP    = offerExn.get("p").toString(); // should match applySaid

        System.out.println("✅ Received offer, offerSaid=" + offerSaid + ", linked to apply=" + offerP);
        IpexNotificationHelper.markAndDelete(client, offerNote);

        // ── STEP 3: Send /ipex/agree ──────────────────────────────────────────
        IpexAgreeArgs agreeArgs = IpexAgreeArgs.builder()
                .senderName(identifierName)
                .recipient(aid)
                .offerSaid(offerSaid)       // link back to the offer
                .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                .build();

        Exchanging.ExchangeMessageResult agreeResult = client.ipex().agree(agreeArgs);
        String agreeSaid = agreeResult.exn().getKed().get("d").toString();

        Object agreeOp = client.ipex().submitAgree(
                identifierName,
                agreeResult.exn(),
                agreeResult.sigs(),
                Collections.singletonList(aid)
        );
        client.operations().wait(Operation.fromObject(agreeOp));

        IpexNotificationHelper.Notification grantNote =
                IpexNotificationHelper.waitForNotification(client, "/exn/ipex/grant");

        Object grantExchange = client.exchanges().get(grantNote.a.d).get();
        @SuppressWarnings("unchecked")
        Map<String, Object> grantExn = (Map<String, Object>)
                ((Map<String, Object>) grantExchange).get("exn");
        IpexNotificationHelper.markAndDelete(client, grantNote);

        @SuppressWarnings("unchecked")
        Map<String, Object> grantBody = (Map<String, Object>) grantExn.get("e");
        // Getting extra Arguments
        Map<String, Object> acdc = (Map<String, Object>) grantBody.get("acdc");
        Map<String, String> attributes = (Map<String, String>) acdc.get("a");

        // Foundation Credential fields
        String email = attributes.get("email");
        String firstName = attributes.get("firstName");
        String lastName = attributes.get("lastName");

        // Persist credential fields to KYCEntity
        KYCEntity entity = kycEntity.get();
        entity.setEmail(email);
        entity.setFirstName(firstName);
        entity.setLastName(lastName);
        kycRepository.save(entity);

        log.info("✅ IPEX agree sent, agreeSaid={} | KYC stored for session={}", agreeSaid, sessionId);
        return ResponseEntity.ok(Map.of(
                "email", email != null ? email : "",
                "firstName", firstName != null ? firstName : "",
                "lastName", lastName != null ? lastName : ""
        ));
    }

    /** Returns the current KYC + Cardano state for a session. */
    @GetMapping("/session")
    public ResponseEntity<?> getSession(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null) {
            return ResponseEntity.ok(Map.of("exists", false));
        }
        Optional<KYCEntity> opt = kycRepository.findById(sessionId);
        if (opt.isEmpty()) {
            return ResponseEntity.ok(Map.of("exists", false));
        }
        KYCEntity kyc = opt.get();
        boolean hasCredential = kyc.getFirstName() != null || kyc.getEmail() != null;
        boolean hasCardanoAddress = kyc.getCardanoAddress() != null;

        Map<String, Object> result = new HashMap<>();
        result.put("exists", true);
        result.put("hasCredential", hasCredential);
        result.put("hasCardanoAddress", hasCardanoAddress);
        if (hasCredential) {
            result.put("firstName", kyc.getFirstName() != null ? kyc.getFirstName() : "");
            result.put("lastName", kyc.getLastName() != null ? kyc.getLastName() : "");
            result.put("email", kyc.getEmail() != null ? kyc.getEmail() : "");
        }
        if (hasCardanoAddress) {
            result.put("cardanoAddress", kyc.getCardanoAddress());
        }
        return ResponseEntity.ok(result);
    }

    /** Persists the user's Cardano address (called after CIP-30 wallet connection). */
    @PostMapping("/session/cardano-address")
    public ResponseEntity<?> storeCardanoAddress(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, String> body) {
        if (sessionId == null || !kycRepository.existsById(sessionId)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        String cardanoAddress = body.get("cardanoAddress");
        if (cardanoAddress == null || cardanoAddress.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cardanoAddress is required"));
        }
        KYCEntity kyc = kycRepository.findById(sessionId).get();
        kyc.setCardanoAddress(cardanoAddress);
        kycRepository.save(kyc);
        log.info("Stored Cardano address for session={}: {}…", sessionId, cardanoAddress.substring(0, Math.min(20, cardanoAddress.length())));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Builds an unsigned WL Add transaction using the Cardano address stored for this session.
     * The entity signs the user's PKH off-chain using keri.signing-mnemonic.
     * No request body — address is read from the KYCEntity in the DB.
     */
    @PostMapping("/allowlist/build-add-tx")
    public ResponseEntity<?> buildAllowListAddTx(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null || !kycRepository.existsById(sessionId)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        KYCEntity kyc = kycRepository.findById(sessionId).get();
        String userAddress = kyc.getCardanoAddress();
        if (userAddress == null || userAddress.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No Cardano address on record — please connect your wallet first"));
        }
        try {
            String txCbor = allowListService.buildAddTxWithEndorsement(userAddress, signingMnemonic);
            return ResponseEntity.ok(Map.of("txCbor", txCbor));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("allowlist build-add-tx failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
