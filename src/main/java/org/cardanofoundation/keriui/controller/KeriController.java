package org.cardanofoundation.keriui.controller;

import lombok.RequiredArgsConstructor;
import org.cardanofoundation.keriui.domain.IdentifierConfig;
import org.cardanofoundation.keriui.domain.entity.KYCEntity;
import org.cardanofoundation.keriui.domain.repository.KycRepository;
import org.cardanofoundation.keriui.domain.responses.GetOobiResponse;
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
public class KeriController {

    private final IdentifierConfig identifierConfig;
    private final SignifyClient client;
    private final KycRepository kycRepository;
    private static final Pattern OOBI_AID_PATTERN = Pattern.compile("/oobi/([^/]+)");
    @Value("${keri.identifier.name}")
    private String identifierName;
    @Value("${keri.schema.said}")
    private String schemaSaid;
    @Value("${keri.schema.base-url}")
    private String schemaBaseUrl;

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
    public ResponseEntity<String> presentCredential(
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
        System.out.println("✅ IPEX agree sent, agreeSaid=" + agreeSaid);
        return ResponseEntity.ok("Ok");
    }
}
