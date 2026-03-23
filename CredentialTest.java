///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//REPOS Central,sonatype-snapshots=https://central.sonatype.com/repository/maven-snapshots/
//DEPS org.cardanofoundation:signify:0.1.2-PR62-d6aea58
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.core:jackson-core:2.17.2
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.17.2

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.app.credentialing.ipex.*;
import org.cardanofoundation.signify.app.credentialing.registries.CreateRegistryArgs;
import org.cardanofoundation.signify.app.credentialing.registries.RegistryResult;
import org.cardanofoundation.signify.app.credentialing.credentials.*;
import org.cardanofoundation.signify.cesr.Keeping.Keeper;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.cesr.Serder;
import org.cardanofoundation.signify.cesr.Siger;
import org.cardanofoundation.signify.cesr.exceptions.LibsodiumException;
import org.cardanofoundation.signify.cesr.util.Utils;
import org.cardanofoundation.signify.core.Eventing;
import org.cardanofoundation.signify.core.States;
import org.cardanofoundation.signify.core.States.HabState;

import java.io.IOException;
import java.net.URI;
import java.security.DigestException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JBang script: issues a FoundationEmployeeCredential and IPEX-grants it to a holder.
 *
 * Environment variables (all optional, defaults shown):
 *   KERIA_URL            – http://localhost:3901
 *   KERIA_BOOT_URL       – http://localhost:3903
 *   KERIA_BRAN           – 0ADF2TpptgqcDE5IQUF1H
 *   KERIA_IDENTIFIER_NAME – identity
 *   KERIA_SCHEMA_SAID    – EL9oOWU_7zQn_rD--Xsgi3giCWnFDaNvFMUGTOZx1ARO
 *   KERIA_SCHEMA_BASE_URL – https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi/
 *   RECIPIENT_OOBI       – (if set, skip interactive prompt)
 *   CREDENTIAL_FIRST_NAME – John
 *   CREDENTIAL_LAST_NAME  – Doe
 *   CREDENTIAL_EMAIL      – john.doe@cardanofoundation.org
 */
public class CredentialTest {

    static final String KERIA_URL         = env("KERIA_URL",             "http://localhost:3901");
    static final String KERIA_BOOT_URL    = env("KERIA_BOOT_URL",        "http://localhost:3903");
    static final String BRAN              = env("KERIA_BRAN",            "0ADF2TpptgqcDE5IQUF1H");
    static final String IDENTIFIER_NAME   = env("KERIA_IDENTIFIER_NAME", "identity");
    static final String SCHEMA_SAID       = env("KERIA_SCHEMA_SAID",     "EL9oOWU_7zQn_rD--Xsgi3giCWnFDaNvFMUGTOZx1ARO");
    static final String SCHEMA_BASE_URL   = env("KERIA_SCHEMA_BASE_URL", "https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi/");
    static final String FIRST_NAME        = env("CREDENTIAL_FIRST_NAME", "John");
    static final String LAST_NAME         = env("CREDENTIAL_LAST_NAME",  "Doe");
    static final String EMAIL             = env("CREDENTIAL_EMAIL",      "john.doe@cardanofoundation.org");

    static final DateTimeFormatter KERI_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'");
    static final Pattern OOBI_AID_PATTERN = Pattern.compile("/oobi/([^/]+)");
    static final int    MAX_RETRIES       = 60;
    static final long   POLL_MS           = 2_000;
    static final long   OP_TIMEOUT_MS     = 120_000;

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        // ── 1. Connect to KERIA ──────────────────────────────────────────────
        System.out.println("Connecting to KERIA at " + KERIA_URL + " ...");
        SignifyClient client = new SignifyClient(KERIA_URL, BRAN, Salter.Tier.low, KERIA_BOOT_URL, null);
        try {
            client.connect();
            System.out.println("Connected to existing KERIA agent.");
        } catch (Exception e) {
            System.out.println("No existing agent found, booting a new one...");
            client.boot();
            client.connect();
            System.out.println("New KERIA agent booted and connected.");
        }

        // ── 2. Get or create identifier ──────────────────────────────────────
        String prefix;
        Optional<States.HabState> habState = client.identifiers().get(IDENTIFIER_NAME);
        if (habState.isPresent()) {
            prefix = habState.get().getPrefix();
            System.out.println("Using existing identifier '" + IDENTIFIER_NAME + "': " + prefix);
        } else {
            System.out.println("Identifier '" + IDENTIFIER_NAME + "' not found, creating...");
            prefix = createAid(client, IDENTIFIER_NAME);
            System.out.println("Created identifier: " + prefix);
        }

        // Resolve schema OOBI so credential validation works
        System.out.println("Resolving schema OOBI...");
        Object schemaResolveOp = client.oobis().resolve(SCHEMA_BASE_URL + SCHEMA_SAID, null);
        waitOp(client, Operation.fromObject(schemaResolveOp));

        // ── 3. Print our own OOBI ────────────────────────────────────────────
        Optional<Object> oobiResult = client.oobis().get(IDENTIFIER_NAME, null);
        if (oobiResult.isEmpty()) {
            throw new IllegalStateException("Could not retrieve OOBI for identifier '" + IDENTIFIER_NAME + "'");
        }
        @SuppressWarnings("unchecked")
        List<String> oobis = (List<String>) ((Map<String, Object>) oobiResult.get()).get("oobis");
        if (oobis == null || oobis.isEmpty()) {
            throw new IllegalStateException("OOBI list is empty for identifier '" + IDENTIFIER_NAME + "'");
        }
        String myOobi = oobis.getFirst();
        System.out.println();
        System.out.println("════════════════════════════════════════");
        System.out.println("  MY OOBI (share this with the holder)  ");
        System.out.println("════════════════════════════════════════");
        System.out.println(myOobi);
        System.out.println("════════════════════════════════════════");
        System.out.println();

        // ── 4. Get recipient OOBI from env or prompt ─────────────────────────
        // String recipientOobi = System.getenv("RECIPIENT_OOBI");
        // String recipientOobi = env("RECIPIENT_OOBI", "http://keria:3902/oobi/EL_rUwsOJ6rGtYmKWNUd1f6_CmsySqoEea6g2L3UFHYP/agent/EAQa7y6bKK7bFmf2dSbKlmI9M0a1TbzO5pLmrt8bcVu8");
        String recipientOobi = "http://keria:3902/oobi/EL_rUwsOJ6rGtYmKWNUd1f6_CmsySqoEea6g2L3UFHYP/agent/EAQa7y6bKK7bFmf2dSbKlmI9M0a1TbzO5pLmrt8bcVu8";
        if (recipientOobi == null || recipientOobi.isBlank()) {
            System.out.print("Enter the holder's OOBI (or set RECIPIENT_OOBI env var): ");
            System.out.flush();
            try (Scanner scanner = new Scanner(System.in)) {
                recipientOobi = scanner.nextLine().trim();
            }
        } else {
            System.out.println("Using RECIPIENT_OOBI from environment: " + recipientOobi);
        }

        // Resolve recipient OOBI
        System.out.println("Resolving recipient OOBI...");
        Object recipientResolveOp = client.oobis().resolve(recipientOobi, null);
        waitOp(client, Operation.fromObject(recipientResolveOp));

        // Extract AID from OOBI path
        Matcher matcher = OOBI_AID_PATTERN.matcher(URI.create(recipientOobi).getPath());
        if (!matcher.find()) {
            throw new IllegalArgumentException("No AID found in OOBI: " + recipientOobi);
        }
        String recipientAid = matcher.group(1);
        System.out.println("Recipient AID: " + recipientAid);

        // ── 5. Create credential registry ────────────────────────────────────
        // Always create a fresh registry so its anchor ixn is guaranteed to be
        // in the current identifier's KEL.  Reusing an old registry from a
        // previous agent session would cause KERIA to report
        // "Credential registry missing anchor for inception".

        String regk = "";
        String registryName = "IpexTestRegistry";
        try {

            CreateRegistryArgs registryArgs = CreateRegistryArgs.builder().build();
            registryArgs.setName(IDENTIFIER_NAME);
            registryArgs.setRegistryName(registryName);
            System.out.println("Creating credential registry...");
            RegistryResult regResult = client.registries().create(registryArgs);
            System.out.println("Registry creation submitted, waiting for witness confirmation...");
            client.operations().wait(Operation.fromObject(Operation.fromObject(regResult.op())));
            regk = regResult.getRegser().getPre();
        } catch (Exception e) {
            System.out.println("Registry creation failed, attempting to continue if it's because the registry already exists...");
            if (!e.getMessage().contains("already exists")) {
                System.out.println("Registry already exists");
            }
        }
        List<Map<String, Object>> registryList = (List<Map<String, Object>>)client.registries().list(IDENTIFIER_NAME);
        regk = (String) ((Map<String, Object>) ((List<?>) registryList).getFirst()).get("regk");
        // Use the regk straight from the create result — avoids a round-trip
        // and ensures we reference exactly the registry whose anchor we just anchored.

        System.out.println("Registry created: " + regk);
        System.out.println("Number of registries for identifier '" + IDENTIFIER_NAME + "': " + registryList.size());
        // ── 6. Issue credential ──────────────────────────────────────────────
        // Use setDt() so Credentials.issue() picks it up from getDt().
        // Putting dt only in additionalProperties would cause it to be
        // overwritten by Utils.currentDateTimeString() inside issue().
        CredentialData.CredentialSubject subject =
                CredentialData.CredentialSubject.builder().build();
        subject.setI(recipientAid);
        // subject.setDt(LocalDateTime.now(ZoneOffset.UTC).format(KERI_DATETIME));
        Map<String, Object> additionalProperties = new HashMap<>();
        additionalProperties.put("firstName", FIRST_NAME);
        additionalProperties.put("lastName", LAST_NAME);
        additionalProperties.put("email", EMAIL);
        subject.setAdditionalProperties(additionalProperties);

        CredentialData credentialData = CredentialData.builder().build();
        credentialData.setA(subject);
        credentialData.setS(SCHEMA_SAID);
        credentialData.setRi(regk);

        System.out.println("Issuing credential...");
        IssueCredentialResult result =
                client.credentials().issue(IDENTIFIER_NAME, credentialData);
        Operation<Object> wait = client.operations().wait(Operation.fromObject(result.getOp()));
        System.out.println("Credential issuance operation done: " +wait.isDone());
        String credentialId = result.getAcdc().getKed().get("d").toString();
        System.out.println("Credential issued with ID: " + credentialId);
        System.out.println(result);

        // ── 7. IPEX grant (deliver credential to recipient) ───────────────────
        System.out.println("Sending credential via IPEX grant...");
        LinkedHashMap<String, Object> credentialMap = (LinkedHashMap<String, Object>) client.credentials().get(credentialId, false).orElseThrow(
                () -> new IllegalStateException("Credential not found: " + credentialId));
        @SuppressWarnings("unchecked")
        Map<String, Object> sad = (Map<String, Object>) credentialMap.get("sad");
        @SuppressWarnings("unchecked")
        Map<String, Object> anc = (Map<String, Object>) credentialMap.get("anc");
        @SuppressWarnings("unchecked")
        Map<String, Object> iss = (Map<String, Object>) credentialMap.get("iss");
        @SuppressWarnings("unchecked")
        List<String> ancatc = (List<String>) credentialMap.get("ancatc");
        String ancAttachment = (ancatc != null && !ancatc.isEmpty()) ? ancatc.getFirst() : null;

        IpexGrantArgs gArgs = IpexGrantArgs.builder().build();
        gArgs.setSenderName(IDENTIFIER_NAME);
        gArgs.setAcdc(new Serder(sad));
        gArgs.setAnc(new Serder(anc));
        gArgs.setIss(new Serder(iss));
        gArgs.setAncAttachment(ancAttachment);
        gArgs.setRecipient(recipientAid);
        gArgs.setDatetime(LocalDateTime.now(ZoneOffset.UTC).format(KERI_DATETIME));



        Exchanging.ExchangeMessageResult grantResult = grant(client, gArgs);
        Object grantOp = client.ipex().submitGrant(
                IDENTIFIER_NAME, grantResult.exn(), grantResult.sigs(), grantResult.atc(),
                Collections.singletonList(recipientAid));
        waitOp(client, Operation.fromObject(grantOp));
        System.out.println("IPEX grant submitted. Credential ID: " + credentialId);

    }

    static Exchanging.ExchangeMessageResult grant(SignifyClient client, IpexGrantArgs args) throws InterruptedException, DigestException, IOException, LibsodiumException {
        HabState hab = client.identifiers().get(args.getSenderName())
                .orElseThrow(() -> new IllegalArgumentException("Identifier not found: " + args.getSenderName()));
        Map<String, Object> data = Map.of(
                "m", args.getMessage() != null ? args.getMessage() : "",
                "a", Map.of("oobiUrl", SCHEMA_BASE_URL),
                "s", SCHEMA_SAID
        );

        return client
                .exchanges()
                .createExchangeMessage(
                        hab,
                        "/ipex/grant",
                        data,
                        new LinkedHashMap<>(),
                        args.getRecipient(),
                        args.getDatetime(),
                        args.getAgreeSaid()
                );
    }


    static Optional<String> waitForCredential(SignifyClient client, String credentialId)
            throws InterruptedException {
        int maxAttempts = 10;
        int waitTimeMs = 2000;

        System.out.println("Waiting for credential to be available in store...");

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Optional<String> credential = client.credentials().get(credentialId);
                if (credential.isPresent()) {
                    System.out.println("Credential retrieved successfully!");
                    return credential;
                }
            } catch (Exception e) {
                System.out.println("Attempt " + attempt + " failed: " + e.getMessage());
            }

            if (attempt < maxAttempts) {
                System.out.println("Credential not yet available, waiting... (attempt " + attempt + "/" + maxAttempts + ")");
                Thread.sleep(waitTimeMs);
            }
        }

        return Optional.empty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static <T> Operation<T> waitOp(SignifyClient client, Operation<T> op) throws Exception {
        // Use no-args constructor so the `aborted` AtomicBoolean field initializer runs,
        // then set the timeout via setter — avoids the @Builder all-args constructor
        // bypassing the field initializer for `private final AtomicBoolean aborted`.
        Operations.AbortSignal signal = new Operations.AbortSignal();
        signal.setTimeout(OP_TIMEOUT_MS);
        Operations.WaitOptions opts = Operations.WaitOptions.builder()
                .abortSignal(signal)
                .build();
        return client.operations().wait(op, opts);
    }

    static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    static Notification waitForNotification(SignifyClient client, String route) throws Exception {
        for (int i = 0; i < MAX_RETRIES; i++) {
            Notifying.Notifications.NotificationListResponse response = client.notifications().list();
            List<Notification> notes = Utils.fromJson(response.notes(), new TypeReference<>() {});
            Optional<Notification> match = notes.stream()
                    .filter(n -> route.equals(n.a.r) && !Boolean.TRUE.equals(n.r))
                    .findFirst();
            if (match.isPresent()) return match.get();
            System.out.println("  Waiting for " + route + " (attempt " + (i + 1) + "/" + MAX_RETRIES + ")...");
            Thread.sleep(POLL_MS);
        }
        throw new RuntimeException("Timed out waiting for notification: " + route);
    }

    static void markAndDelete(SignifyClient client, Notification note) throws Exception {
        client.notifications().mark(note.i);
        client.notifications().delete(note.i);
    }

    static String createAid(SignifyClient client, String name) throws Exception {
        // Discover witnesses from agent config
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) new Coring.Config(client).get();
        @SuppressWarnings("unchecked")
        List<String> iurls = (List<String>) config.get("iurls");
        if (iurls == null || iurls.isEmpty()) {
            throw new IllegalStateException("Agent configuration has no iurls (witnesses)");
        }

        Map<String, String> witnessMap = new LinkedHashMap<>();
        for (String oobi : iurls) {
            String[] parts = oobi.split("/oobi/");
            if (parts.length > 1) {
                String eid = parts[1].split("/")[0];
                witnessMap.putIfAbsent(eid, oobi);
            }
        }
        List<String> witnessIds = new ArrayList<>(witnessMap.keySet());
        int size = witnessIds.size();
        int toad = size >= 3 ? (size * 2 / 3 + 1) : size;

        CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
        kArgs.setToad(toad);
        kArgs.setWits(witnessIds);

        EventResult result = client.identifiers().create(name, kArgs);
        Operation<?> op = waitOp(client, Operation.fromObject(result.op()));
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) op.getResponse();
        String prefix = resp.get("i").toString();

        // Add agent end-role so OOBI is publishable
        String eid = client.getAgent() != null ? client.getAgent().getPre() : null;
        if (eid != null && !eid.isBlank()) {
            EventResult roleResult = client.identifiers().addEndRole(name, "agent", eid, null);
            waitOp(client, Operation.fromObject(roleResult.op()));
        }
        return prefix;
    }

    // ── Notification model ────────────────────────────────────────────────────

    public static class Notification {
        public String i;
        public Boolean r;
        public NotificationBody a;

        public static class NotificationBody {
            public String r;
            public String d;
        }
    }
}