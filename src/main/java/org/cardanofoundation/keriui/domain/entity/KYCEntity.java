package org.cardanofoundation.keriui.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "kyc")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KYCEntity {

    @Id
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "aid", length = 128)
    private String aid;

    @Column(name = "oobi", columnDefinition = "text")
    private String oobi;

    @Column(name = "cardano_address", length = 255)
    private String cardanoAddress;

    @Column(name = "credential_attributes", columnDefinition = "text")
    private String credentialAttributes;

    @Column(name = "credential_role")
    private Integer credentialRole;

    /** Transaction hash of the Allow List Add tx. Set after the user joins the Allow List. */
    @Column(name = "allowlist_tx_hash", length = 64)
    private String allowListTxHash;
}
