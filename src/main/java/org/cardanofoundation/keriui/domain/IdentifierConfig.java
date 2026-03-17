package org.cardanofoundation.keriui.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IdentifierConfig {

    private String prefix;
    private String name;
    private String role;
}
