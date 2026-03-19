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
@Table(name = "tel_config")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TelConfigEntity {

    @Id
    @Builder.Default
    private Integer id = 1;

    @Column(name = "script_address", nullable = false)
    private String scriptAddress;

    @Column(name = "policy_id", nullable = false)
    private String policyId;

    @Column(name = "boot_tx_hash", nullable = false)
    private String bootTxHash;

    @Column(name = "boot_index", nullable = false)
    private Integer bootIndex;

    @Column(name = "issuer_vkey", nullable = false)
    private String issuerVkey;
}
