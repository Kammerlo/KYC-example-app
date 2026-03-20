package org.cardanofoundation.keriui.controller;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.config.SchemaConfig;
import org.cardanofoundation.keriui.domain.Role;
import org.cardanofoundation.keriui.domain.dto.AvailableRolesResponse;
import org.cardanofoundation.keriui.domain.dto.CredentialResponse;
import org.cardanofoundation.keriui.domain.dto.IdentifierConfig;
import org.cardanofoundation.keriui.domain.dto.OobiResponse;
import org.cardanofoundation.keriui.domain.dto.RoleInfo;
import org.cardanofoundation.keriui.domain.dto.RoleOption;
import org.cardanofoundation.keriui.domain.dto.SchemaItem;
import org.cardanofoundation.keriui.domain.dto.SchemaListResponse;
import org.cardanofoundation.keriui.domain.dto.SessionResponse;
import org.cardanofoundation.keriui.domain.entity.KYCEntity;
import org.cardanofoundation.keriui.domain.repository.KycRepository;
import org.cardanofoundation.keriui.service.AllowListService;
import org.cardanofoundation.keriui.service.TelService;
import org.cardanofoundation.keriui.util.IpexNotificationHelper;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialData;
import org.cardanofoundation.signify.app.credentialing.credentials.IssueCredentialResult;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAgreeArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexApplyArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexGrantArgs;
import org.cardanofoundation.signify.app.credentialing.registries.CreateRegistryArgs;
import org.cardanofoundation.signify.app.credentialing.registries.RegistryResult;
import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    private final TelService telService;
    private final SchemaConfig schemaConfig;
    private final ObjectMapper objectMapper;

    /** Regex to extract the AID from an OOBI URL path segment. */
    private static final Pattern OOBI_AID_PATTERN = Pattern.compile("/oobi/([^/]+)");

    /** ISO-8601 datetime format expected by the KERI agent for IPEX messages. */
    private static final DateTimeFormatter KERI_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'");

    @Value("${keri.identifier.name}")
    private String identifierName;

    @Value("${keri.identifier.registry-name}")
    private String registryName;

    /** Mnemonic of the Trusted Entity that signs WL Add endorsements. */
    @Value("${keri.signing-mnemonic}")
    private String signingMnemonic;

    /** Tracks in-flight presentation threads so they can be interrupted by /credential/cancel. */
    private final ConcurrentHashMap<String, Thread> activePresentations = new ConcurrentHashMap<>();

    // ── OOBI ──────────────────────────────────────────────────────────────────

    /** Returns the KERI agent's OOBI URL so wallets can resolve this issuer's identifier. */
    @GetMapping("/oobi")
    public ResponseEntity<OobiResponse> getOobi(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId)
            throws IOException, InterruptedException {
        Optional<Object> o = client.oobis().get(identifierConfig.getName(), null);
        if (o.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> oobiMap = (Map<String, Object>) o.get();
        @SuppressWarnings("unchecked")
        List<String> oobis = (List<String>) oobiMap.get("oobis");
        if (oobis.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new OobiResponse(oobis.getFirst()));
    }

    /** Resolve a wallet OOBI and persist a KYC session record for the given AID. */
    @GetMapping("/oobi/resolve")
    public ResponseEntity<Boolean> resolveOobi(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam String oobi) throws IOException, InterruptedException {
        Object resolve = client.oobis().resolve(oobi, sessionId);
        Operation<Object> wait = client.operations().wait(Operation.fromObject(resolve));

        if (wait.isDone()) {
            Matcher matcher = OOBI_AID_PATTERN.matcher(URI.create(oobi).getPath());
            if (!matcher.find()) {
                throw new IllegalArgumentException("No AID found in OOBI URL: " + oobi);
            }
            String aid = matcher.group(1);
            client.contacts().get(aid);
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

    // ── Schema / role discovery ───────────────────────────────────────────────

    /**
     * Returns all configured credential schemas with their role, label and SAID.
     * The frontend uses this to show which credential types are available.
     */
    @GetMapping("/schemas")
    public ResponseEntity<SchemaListResponse> getSchemas() {
        if (schemaConfig.getSchemas() == null) {
            return ResponseEntity.ok(new SchemaListResponse(List.of()));
        }
        List<SchemaItem> schemas = new ArrayList<>();
        for (Map.Entry<String, SchemaConfig.SchemaEntry> entry : schemaConfig.getSchemas().entrySet()) {
            try {
                Role role = Role.fromString(entry.getKey());
                schemas.add(new SchemaItem(entry.getKey(), role.getValue(),
                        entry.getValue().getLabel(), entry.getValue().getSaid()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown role name in schema config: {}", entry.getKey());
            }
        }
        schemas.sort((a, b) -> Integer.compare(a.roleValue(), b.roleValue()));
        return ResponseEntity.ok(new SchemaListResponse(schemas));
    }

    /**
     * Returns the credential roles that the signing entity is authorised to endorse.
     * A TE with role R can only assign roles 0..R to users (enforced on-chain).
     */
    @GetMapping("/available-roles")
    public ResponseEntity<?> getAvailableRoles() {
        try {
            // Derive signing entity's PKH from the configured mnemonic
            Account entityAccount = Account.createFromMnemonic(Networks.preview(), signingMnemonic);
            String entityPkh = HexUtil.encodeHexString(
                    entityAccount.hdKeyPair().getPublicKey().getKeyHash());

            RoleInfo entityRoleInfo = telService.roleInfoForPkh(entityPkh);
            Role maxRole = entityRoleInfo.teRole();
            if (maxRole == null) {
                // Signing entity is not in the TEL — no roles can be assigned
                return ResponseEntity.ok(new AvailableRolesResponse(List.of(), -1, null));
            }

            List<RoleOption> availableRoles = new ArrayList<>();
            if (schemaConfig.getSchemas() != null) {
                for (Map.Entry<String, SchemaConfig.SchemaEntry> entry : schemaConfig.getSchemas().entrySet()) {
                    try {
                        Role role = Role.fromString(entry.getKey());
                        if (role.getValue() <= maxRole.getValue()) {
                            availableRoles.add(new RoleOption(entry.getKey(), role.getValue(),
                                    entry.getValue().getLabel()));
                        }
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown role name in schema config: {}", entry.getKey());
                    }
                }
            }
            availableRoles.sort((a, b) -> Integer.compare(a.roleValue(), b.roleValue()));
            return ResponseEntity.ok(
                    new AvailableRolesResponse(availableRoles, maxRole.getValue(), maxRole.name()));
        } catch (Exception e) {
            log.error("Failed to determine available roles", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── IPEX credential exchange ───────────────────────────────────────────────

    /**
     * Runs the full IPEX apply→offer→agree→grant flow to receive a credential from the user's wallet.
     * Stores the dynamic ACDC attributes in the KYC record and returns them to the frontend.
     *
     * @param roleName the credential role to request (e.g. "USER", "INSTITUTIONAL", "VLEI")
     */
    @GetMapping("/credential/present")
    public ResponseEntity<?> presentCredential(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "role", defaultValue = "USER") String roleName) throws Exception {
        Optional<KYCEntity> kycEntity = kycRepository.findById(sessionId);
        if (kycEntity.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Role role;
        try {
            role = Role.fromString(roleName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown role: " + roleName));
        }

        SchemaConfig.SchemaEntry schemaEntry = schemaConfig.getSchemaForRole(role);
        if (schemaEntry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No schema configured for role: " + roleName));
        }

        String aid = kycEntity.get().getAid();

        // Register this thread so the /credential/cancel endpoint can interrupt it
        activePresentations.put(sessionId, Thread.currentThread());
        try {
            // Step 1: Send /ipex/apply — request the credential from the wallet
            IpexApplyArgs applyArgs = IpexApplyArgs.builder()
                    .recipient(aid)
                    .senderName(identifierName)
                    .schemaSaid(schemaEntry.getSaid())
                    .attributes(Map.of("oobiUrl", schemaConfig.getBaseUrl()))
                    .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                    .build();
            Exchanging.ExchangeMessageResult applyResult = client.ipex().apply(applyArgs);
            Object applyOp = client.ipex().submitApply(identifierName, applyResult.exn(),
                    applyResult.sigs(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(applyOp));

            // Step 2: Wait for /ipex/offer from the wallet
            log.info("Waiting for wallet to respond with an offer...");
            IpexNotificationHelper.Notification offerNote =
                    IpexNotificationHelper.waitForNotification(client, "/exn/ipex/offer");

            @SuppressWarnings("unchecked")
            Map<String, Object> offerExn = (Map<String, Object>)
                    ((LinkedHashMap<String, Object>) client.exchanges().get(offerNote.a.d).get()).get("exn");

            String offerSaid = offerExn.get("d").toString();
            String offerP    = offerExn.get("p").toString();
            log.info("Received offer: offerSaid={} linked to apply={}", offerSaid, offerP);
            IpexNotificationHelper.markAndDelete(client, offerNote);

            // Step 3: Send /ipex/agree — accept the offer
            IpexAgreeArgs agreeArgs = IpexAgreeArgs.builder()
                    .senderName(identifierName)
                    .recipient(aid)
                    .offerSaid(offerSaid)
                    .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                    .build();
            Exchanging.ExchangeMessageResult agreeResult = client.ipex().agree(agreeArgs);
            String agreeSaid = agreeResult.exn().getKed().get("d").toString();

            Object agreeOp = client.ipex().submitAgree(identifierName, agreeResult.exn(),
                    agreeResult.sigs(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(agreeOp));

            // Step 4: Wait for /ipex/grant — the wallet sends the credential
            IpexNotificationHelper.Notification grantNote =
                    IpexNotificationHelper.waitForNotification(client, "/exn/ipex/grant");

            @SuppressWarnings("unchecked")
            Map<String, Object> grantExn = (Map<String, Object>)
                    ((Map<String, Object>) client.exchanges().get(grantNote.a.d).get()).get("exn");

            IpexAdmitArgs admitArgs = IpexAdmitArgs.builder()
                            .senderName(identifierName)
                            .recipient(aid)
                            .grantSaid(grantExn.get("d").toString())
                            .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                            .message("")
                            .build();
            Exchanging.ExchangeMessageResult admit = client.ipex().admit(admitArgs);
            Object o = client.ipex().submitAdmit(identifierName, admit.exn(), admit.sigs(), agreeResult.atc(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(o));
            IpexNotificationHelper.markAndDelete(client, grantNote);

            // Extract all ACDC attributes, removing internal KERI fields
            @SuppressWarnings("unchecked")
            Map<String, Object> acdc = (Map<String, Object>)
                    ((Map<String, Object>) grantExn.get("e")).get("acdc");
            @SuppressWarnings("unchecked")
            Map<String, Object> rawAttributes = (Map<String, Object>) acdc.get("a");

            Map<String, Object> userAttributes = new LinkedHashMap<>(rawAttributes);
            userAttributes.remove("i"); // issuer AID

            // Persist credential to the KYC record
            KYCEntity entity = kycEntity.get();
            try {
                entity.setCredentialAid(acdc.get("d").toString());
                entity.setCredentialSaid(schemaEntry.getSaid());
                entity.setCredentialAttributes(objectMapper.writeValueAsString(userAttributes));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize credential attributes", e);
            }
            entity.setCredentialRole(role.getValue());
            kycRepository.save(entity);

            log.info("IPEX agree sent: agreeSaid={} | KYC stored: session={} role={}", agreeSaid, sessionId, roleName);
            return ResponseEntity.ok(new CredentialResponse(role.name(), role.getValue(),
                    schemaEntry.getLabel(), userAttributes));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Credential presentation cancelled for session={}", sessionId);
            return ResponseEntity.status(409).body(Map.of("error", "Presentation cancelled."));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Timed out")) {
                log.info("Credential presentation timed out for session={}", sessionId);
                return ResponseEntity.status(408)
                        .body(Map.of("error", "No credential was received — the wallet did not respond in time."));
            }
            throw e;
        } finally {
            activePresentations.remove(sessionId);
        }
    }

    /**
     * Interrupts an in-flight credential presentation for the given session.
     * The waiting thread's next {@code Thread.sleep()} will throw {@code InterruptedException},
     * causing {@code presentCredential} to return HTTP 409.
     */
    @PostMapping("/credential/cancel")
    public ResponseEntity<?> cancelPresentation(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-Session-Id header required"));
        }
        Thread t = activePresentations.get(sessionId);
        if (t != null) {
            t.interrupt();
        }
        return ResponseEntity.ok(Map.of("cancelled", t != null));
    }

    // ── Session state ─────────────────────────────────────────────────────────

    /** Returns the current KYC + Cardano state for a session. */
    @GetMapping("/session")
    public ResponseEntity<SessionResponse> getSession(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null) {
            return ResponseEntity.ok(SessionResponse.builder().exists(false).build());
        }
        Optional<KYCEntity> opt = kycRepository.findById(sessionId);
        if (opt.isEmpty()) {
            return ResponseEntity.ok(SessionResponse.builder().exists(false).build());
        }
        KYCEntity kyc = opt.get();
        boolean hasCredential    = kyc.getCredentialAttributes() != null;
        boolean hasCardanoAddress = kyc.getCardanoAddress() != null;

        SessionResponse.SessionResponseBuilder builder = SessionResponse.builder()
                .exists(true)
                .hasCredential(hasCredential)
                .hasCardanoAddress(hasCardanoAddress);

        if (hasCredential) {
            builder.attributes(resolveAttributes(kyc));
            builder.credentialRole(kyc.getCredentialRole() != null ? kyc.getCredentialRole() : 0);
            if (kyc.getCredentialRole() != null) {
                try {
                    builder.credentialRoleName(Role.fromValue(kyc.getCredentialRole()).name());
                } catch (IllegalArgumentException e) {
                    builder.credentialRoleName("USER");
                }
            }
        }
        if (hasCardanoAddress) {
            builder.cardanoAddress(kyc.getCardanoAddress());
        }
        if (kyc.getAllowListTxHash() != null) {
            builder.allowListTxHash(kyc.getAllowListTxHash());
        }
        return ResponseEntity.ok(builder.build());
    }

    /** Persists the user's Cardano address after CIP-30 wallet connection. */
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
        log.info("Stored Cardano address for session={}: {}…",
                sessionId, cardanoAddress.substring(0, Math.min(20, cardanoAddress.length())));

        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Persists the Allow List transaction hash after the user has successfully joined. */
    @PostMapping("/session/allowlist-tx")
    public ResponseEntity<?> storeAllowListTxHash(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, String> body) {
        if (sessionId == null || !kycRepository.existsById(sessionId)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        String txHash = body.get("txHash");
        if (txHash == null || txHash.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "txHash is required"));
        }
        KYCEntity kyc = kycRepository.findById(sessionId).get();
        kyc.setAllowListTxHash(txHash);
        kycRepository.save(kyc);
        log.info("Stored Allow List tx hash for session={}: {}", sessionId, txHash);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Issues a USER credential directly to the wallet's AID for users who don't have one.
     * Issues the ACDC on the KERI agent, grants it via IPEX, stores it in the KYC record,
     * and returns a CredentialResponse so the frontend can proceed to step 4.
     */
    @PostMapping("/credential/issue")
    public ResponseEntity<?> issueCredential(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, String> body) throws Exception {
        if (sessionId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "X-Session-Id header required"));
        }
        Optional<KYCEntity> kycEntityOpt = kycRepository.findById(sessionId);
        if (kycEntityOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String firstName = body.get("firstName");
        String lastName  = body.get("lastName");
        String email     = body.get("email");
        if (firstName == null || lastName == null || email == null ||
                firstName.isBlank() || lastName.isBlank() || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "firstName, lastName and email are required"));
        }

        Role role = Role.USER;
        SchemaConfig.SchemaEntry schemaEntry = schemaConfig.getSchemaForRole(role);
        if (schemaEntry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No USER schema configured"));
        }

        KYCEntity kycEntity = kycEntityOpt.get();
        String walletAid = kycEntity.getAid();
        String datetime  = KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC));

        // Get or create the credential registry
        String registrySaid = getOrCreateRegistrySaid();

        // Build credential subject with the user-supplied attributes
        Map<String, Object> additionalProps = new LinkedHashMap<>();
        additionalProps.put("firstName", firstName);
        additionalProps.put("lastName",  lastName);
        additionalProps.put("email",     email);

        CredentialData.CredentialSubject subject = CredentialData.CredentialSubject.builder()
                .i(walletAid)
                .dt(datetime)
                .additionalProperties(additionalProps)
                .build();

        CredentialData credentialData = CredentialData.builder()
                .i(identifierConfig.getPrefix())
                .ri(registrySaid)
                .s(schemaEntry.getSaid())
                .a(subject)
                .build();

        // Issue the ACDC on the KERI agent
        IssueCredentialResult issueResult = client.credentials().issue(identifierName, credentialData);
        client.operations().wait(issueResult.getOp());

        String credentialSaid = issueResult.getAcdc().getKed().get("d").toString();
        log.info("Issued credential SAID={} for session={}", credentialSaid, sessionId);

        // Grant the credential to the wallet via IPEX
        IpexGrantArgs grantArgs = IpexGrantArgs.builder()
                .senderName(identifierName)
                .recipient(walletAid)
                .message("")
                .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                .acdc(issueResult.getAcdc())
                .iss(issueResult.getIss())
                .anc(issueResult.getAnc())
                .build();
        Exchanging.ExchangeMessageResult grantResult = client.ipex().grant(grantArgs);
        Object grantOp = client.ipex().submitGrant(identifierName, grantResult.exn(),
                grantResult.sigs(), grantResult.atc(), Collections.singletonList(walletAid));
        client.operations().wait(Operation.fromObject(grantOp));

        // Persist credential to the KYC record (same pattern as presentCredential)
        try {
            kycEntity.setCredentialAid(credentialSaid);
            kycEntity.setCredentialSaid(schemaEntry.getSaid());
            kycEntity.setCredentialAttributes(objectMapper.writeValueAsString(additionalProps));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize credential attributes", e);
        }
        kycEntity.setCredentialRole(role.getValue());
        kycRepository.save(kycEntity);

        return ResponseEntity.ok(new CredentialResponse(role.name(), role.getValue(),
                schemaEntry.getLabel(), additionalProps));
    }

    /** Returns the SAID of the first credential registry for this identifier, creating one if needed. */
    @SuppressWarnings("unchecked")
    private String getOrCreateRegistrySaid() throws Exception {
        List<Map<String, Object>> registries =
                (List<Map<String, Object>>) client.registries().list(identifierName);
        if (registries != null && !registries.isEmpty()) {
            return registries.get(0).get("regk").toString();
        }
        log.info("No credential registry found, creating '{}'", registryName);
        CreateRegistryArgs args = CreateRegistryArgs.builder()
                .name(identifierName)
                .registryName(registryName)
                .noBackers(true)
                .build();
        RegistryResult result = client.registries().create(args);
        @SuppressWarnings("unchecked")
        Map<String, Object> opMap = objectMapper.readValue(result.op(), Map.class);
        client.operations().wait(Operation.fromObject(opMap));
        return result.getRegser().getPre();
    }

    /**
     * Builds an unsigned WL Add transaction using the Cardano address stored for this session.
     * The signing entity endorses the user's PKH off-chain; the role comes from the presented credential.
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
            return ResponseEntity.badRequest().body(
                    Map.of("error", "No Cardano address on record — please connect your wallet first"));
        }
        int role = kyc.getCredentialRole() != null ? kyc.getCredentialRole() : 0;
        try {
            MetadataMap cip170Metadata = buildCip170Metadata(kyc, client);
            String txCbor = allowListService.buildAddTxWithEndorsement(userAddress, signingMnemonic, role, cip170Metadata);
            return ResponseEntity.ok(Map.of("txCbor", txCbor));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("allowlist build-add-tx failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private MetadataMap buildCip170Metadata(KYCEntity entity, SignifyClient client) {
        MetadataMap metadata = MetadataBuilder.createMap();
        metadata.put("t", "AUTH_BEGIN");
        metadata.put("i", entity.getAid());
        MetadataMap versionMap = MetadataBuilder.createMap();
        versionMap.put("v", "1.0");
        versionMap.put("a", "ACDC10");
        versionMap.put("k", "KERI10");
        metadata.put("v", versionMap);
        metadata.put("s", entity.getCredentialSaid());
        //        metadata.put("m", ""); // Optional field
        String s = waitForCredential(client, entity.getCredentialAid()).orElseThrow(() -> new IllegalStateException("Credential not found with ID: " + entity.getCredentialAid()));
        List<Map<String, Object>> cesrData = CESRStreamUtil.parseCESRData(s);
        String stripped = striptCesrData(cesrData);
        byte[][] chunks = splitIntoChunks(stripped.getBytes(), 64);
        MetadataList credentialChunks = MetadataBuilder.createList();
        for (byte[] chunk : chunks) {
            credentialChunks.add(chunk);
        }
        metadata.put("c", credentialChunks);
        return metadata;
    }

    private static byte[][] splitIntoChunks(byte[] data, int chunkSize) {
        int numChunks = (data.length + chunkSize - 1) / chunkSize;
        byte[][] chunks = new byte[numChunks][];

        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, data.length);
            chunks[i] = Arrays.copyOfRange(data, start, end);
        }

        return chunks;
    }

    private String striptCesrData(List<Map<String, Object>> cesrData) {
        List<Map<String, Object>> allVcpEvents = new ArrayList<>();
        List<String> allVcpAttachments = new ArrayList<>();
        List<Map<String, Object>> allIssEvents = new ArrayList<>();
        List<String> allIssAttachments = new ArrayList<>();
        List<Map<String, Object>> allAcdcEvents = new ArrayList<>();
        List<String> allAcdcAttachments = new ArrayList<>();

        for (Map<String, Object> eventData : cesrData) {
            Map<String, Object> event = (Map<String, Object>) eventData.get("event");

            // Check for event type
            Object eventTypeObj = event.get("t");
            if (eventTypeObj != null) {
                String eventType = eventTypeObj.toString();
                switch (eventType) {
                    case "vcp":
                        allVcpEvents.add(event);
                        allVcpAttachments.add((String) eventData.get("atc"));
                        break;
                    case "iss":
                        allIssEvents.add(event);
                        allIssAttachments.add((String) eventData.get("atc"));
                        break;
                }
            } else {
                // Check if this is an ACDC (credential data) without "t" field
                if (event.containsKey("s") && event.containsKey("a") && event.containsKey("i")) {
                    Object schemaObj = event.get("s");
                    if (schemaObj != null) {
                        allAcdcEvents.add(event);
                        allAcdcAttachments.add("");
                    }
                }
            }
        }

        List<Map<String, Object>> combinedEvents = new ArrayList<>();
        List<String> combinedAttachments = new ArrayList<>();

        combinedEvents.addAll(allVcpEvents);
        combinedEvents.addAll(allIssEvents);
        combinedEvents.addAll(allAcdcEvents);

        combinedAttachments.addAll(allVcpAttachments);
        combinedAttachments.addAll(allIssAttachments);
        combinedAttachments.addAll(allAcdcAttachments);

        return CESRStreamUtil.makeCESRStream(combinedEvents, combinedAttachments);
    }

    private Optional<String> waitForCredential(SignifyClient client, String credentialId) {
        int maxAttempts = 10;
        int waitTimeMs = 2000;

        log.info("Waiting for credential to be available in store...");

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Optional<String> credential = client.credentials().get(credentialId);
                if (credential.isPresent()) {
                    log.info("Credential retrieved successfully!");
                    return credential;
                }
            } catch (Exception e) {
                log.info("Attempt " + attempt + " failed: " + e.getMessage());
            }

            if (attempt < maxAttempts) {
                log.info("Credential not yet available, waiting... (attempt " + attempt + "/" + maxAttempts + ")");
                try {
                    Thread.sleep(waitTimeMs);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return Optional.empty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Resolve credential attributes from the KYC record.
     * Prefers the JSON column; falls back to the legacy first/last/email columns.
     */
    private Map<String, Object> resolveAttributes(KYCEntity kyc) {
        if (kyc.getCredentialAttributes() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> attrs = objectMapper.readValue(kyc.getCredentialAttributes(), Map.class);
                return attrs;
            } catch (Exception e) {
                log.warn("Failed to parse credential attributes for session={}", kyc.getSessionId(), e);
                return Map.of();
            }
        } else {
            return Map.of();
        }
    }
}
