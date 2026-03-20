package org.cardanofoundation.keriui.config;


import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.cardanofoundation.keriui.config.SchemaConfig;
import org.cardanofoundation.keriui.domain.dto.IdentifierConfig;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.core.States;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;


@Configuration
@Slf4j
public class KeriConfig {

    public record WitnessInfo(String eid, String oobi) {}
    public record AvailableWitnesses(int toad, List<WitnessInfo> witnesses) {}

    @Bean
    public SignifyClient signifyClient(@Value("${keri.url}") String url,
                                       @Value("${keri.identifier.bran}") String bran,
                                       @Value("${keri.booturl}") String bootUrl,
                                       SchemaConfig schemaConfig) throws Exception {
        SignifyClient client = new SignifyClient(url, bran, Salter.Tier.low, bootUrl, null);
        try {
            client.connect();
        } catch (Exception e) {
            client.boot();
            client.connect();
        }

        if (schemaConfig.getSchemas() != null) {
            for (SchemaConfig.SchemaEntry entry : schemaConfig.getSchemas().values()) {
                Object resolve = client.oobis().resolve(schemaConfig.getBaseUrl() + entry.getSaid(), null);
                client.operations().wait(Operation.fromObject(resolve));
            }
        }

        return client;
    }

    @Bean
    public IdentifierConfig createIdentifier(
            @Value("${keri.identifier.name}") String identifierName,
            @Value("${keri.identifier.role}") String role, SignifyClient client) throws Exception {
        String prefix;

        Optional<States.HabState> habState = client.identifiers().get(identifierName);
        if (habState.isPresent()) {
            prefix = habState.get().getPrefix();
        } else {
            log.info("KERI Identifier with name {} not found, creating new one", identifierName);
            prefix = createAid(client, identifierName);
        }
        log.info("Using KERI Identifier with name {} and prefix {}", identifierName, prefix);
        return IdentifierConfig.builder()
                .prefix(prefix)
                .name(identifierName)
                .role(role)
                .build();
    }

    public static String createAid(SignifyClient client, String name) throws Exception {
        Object id = null;
        String eid = "";

        AvailableWitnesses availableWitnesses = getAvailableWitnesses(client);
        List<String> witnessIds = availableWitnesses.witnesses().stream()
                .map(WitnessInfo::eid)
                .toList();

        CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
        kArgs.setToad(availableWitnesses.toad());
        kArgs.setWits(witnessIds);
        Object op, ops;
        Optional<States.HabState> optionalIdentifier = client.identifiers().get(name);
        if (optionalIdentifier.isPresent()) {
            id = optionalIdentifier.get().getPrefix();
        } else {
            log.info("Creating identifier {} with toad {} and witnesses {}", name, availableWitnesses.toad(), witnessIds);
            EventResult result = client.identifiers().create(name, kArgs);
            log.info("Create result object: {}", result);
            op = result.op();
            log.info("Create operation object: {}", op);
            op = client.operations().wait(Operation.fromObject(op));
            log.info("Create operation waited result: {}", op);
            LinkedHashMap<String, Object> resp = (LinkedHashMap<String, Object>) (Operation.fromObject(op).getResponse());

            log.info("Create operation response map: {}", resp);

            id = resp.get("i");
            if (client.getAgent() != null && client.getAgent().getPre() != null) {
                eid = client.getAgent().getPre();
            } else {
                log.warn("Agent or pre is null after create; agent: {}", client.getAgent());
            }
            if (eid == null || eid.isEmpty()) {
                log.warn("No agent EID available after create, this will prevent agent OOBI publishing");
            }

            // After creating identifier, ensure agent endrole exists
            if (!hasEndRole(client, name, "agent", eid)) {
                log.info("Adding agent endrole for identifier {} -> eid {}", name, eid);
                try {
                    EventResult results = client.identifiers().addEndRole(name, "agent", eid, null);
                    log.info("addEndRole result: {}", results);
                    ops = results.op();
                    log.info("addEndRole op: {}", ops);
                    ops = client.operations().wait(Operation.fromObject(ops));
                    log.info("addEndRole op waited result: {}", ops);
                } catch (Exception e) {
                    log.warn("addEndRole failed: {}", e.getMessage());
                }
            }
        }

        // Try to fetch the oobi both by alias (name) and by prefix (id) to see where it was published
        try {
            log.info("Attempting to fetch oobis by alias (name): {}", name);
            Optional<Object> oByName = client.oobis().get(name, "agent");
            log.info("oobis by alias present: {}", oByName.isPresent());
            if (oByName.isPresent()) log.info("oobis by alias raw: {}", oByName.get());
        } catch (Exception e) {
            log.warn("Error fetching oobis by alias: {}", e.getMessage());
        }
        try {
            if (id != null) {
                String prefixStr = id.toString();
                log.info("Attempting to fetch oobis by prefix: {}", prefixStr);
                Optional<Object> oByPrefix = client.oobis().get(prefixStr, "agent");
                log.info("oobis by prefix present: {}", oByPrefix.isPresent());
                if (oByPrefix.isPresent()) log.info("oobis by prefix raw: {}", oByPrefix.get());
            }
        } catch (Exception e) {
            log.warn("Error fetching oobis by prefix: {}", e.getMessage());
        }

        // Finally, return id (prefix)
        Object oId = id;
        String idStr = oId != null ? oId.toString() : null;
        log.info("createAid returning id: {}", idStr);
        return idStr;
    }

    public static AvailableWitnesses getAvailableWitnesses(SignifyClient client) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) new Coring.Config(client).get();

        @SuppressWarnings("unchecked")
        List<String> iurls = (List<String>) config.get("iurls");
        if (iurls == null) {
            throw new IllegalStateException("Agent configuration is missing iurls");
        }

        Map<String, WitnessInfo> witnessMap = new LinkedHashMap<>();
        for (String oobi : iurls) {
            try {
                java.net.URL url = new java.net.URL(oobi);
                if (url != null) {
                    String[] parts = oobi.split("/oobi/");
                    if (parts.length > 1) {
                        String eid = parts[1].split("/")[0];
                        witnessMap.putIfAbsent(eid, new WitnessInfo(eid, oobi));
                    }
                }
            } catch (Exception e) {
                log.warn("Error parsing oobi URL: {} - {}", oobi, e.getMessage());
            }
        }

        List<WitnessInfo> uniqueWitnesses = new ArrayList<>(witnessMap.values());
        int size = uniqueWitnesses.size();

        if (size >= 12) return new AvailableWitnesses(8, uniqueWitnesses.subList(0, 12));
        if (size >= 10) return new AvailableWitnesses(7, uniqueWitnesses.subList(0, 10));
        if (size >= 9) return new AvailableWitnesses(6, uniqueWitnesses.subList(0, 9));
        if (size >= 7) return new AvailableWitnesses(5, uniqueWitnesses.subList(0, 7));
        if (size >= 6) return new AvailableWitnesses(4, uniqueWitnesses.subList(0, 6));
        if (size < 6 && size > 0) return new AvailableWitnesses(size, uniqueWitnesses.subList(0, size));

        throw new IllegalStateException("Insufficient witnesses available");
    }

    public static Boolean hasEndRole(SignifyClient client, String alias, String role, String eid)
            throws Exception {
        List<Map<String, Object>> list = getEndRoles(client, alias, role);
        for (Map<String, Object> endRoleMap : list) {
            String endRole = (String) endRoleMap.get("role");
            String endRoleEid = (String) endRoleMap.get("eid");

            if (endRole != null && endRoleEid != null && endRole.equals(role)
                    && endRoleEid.equals(eid)) {
                return true;
            }
        }
        return false;
    }

    public static List<Map<String, Object>> getEndRoles(SignifyClient client, String alias, String role) throws Exception {
        String path = (role != null)
                ? "/identifiers/" + alias + "/endroles/" + role
                : "/identifiers/" + alias + "/endroles";

        HttpResponse<String> response = client.fetch(path, "GET", alias, null);
        String responseBody = response.body();

        ObjectMapper objectMapper = new ObjectMapper();
        List<Map<String, Object>> result = objectMapper.readValue(responseBody, new TypeReference<>() {
        });
        return result;
    }
}
