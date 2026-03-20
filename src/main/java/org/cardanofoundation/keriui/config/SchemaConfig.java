package org.cardanofoundation.keriui.config;

import lombok.Getter;
import lombok.Setter;
import org.cardanofoundation.keriui.domain.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "keri.schema")
@Getter
@Setter
public class SchemaConfig {

    private String baseUrl;
    private Map<String, SchemaEntry> schemas;

    public SchemaEntry getSchemaForRole(Role role) {
        if (schemas == null) return null;
        return schemas.get(role.name());
    }

    @Getter
    @Setter
    public static class SchemaEntry {
        private String said;
        private String label;
    }
}
