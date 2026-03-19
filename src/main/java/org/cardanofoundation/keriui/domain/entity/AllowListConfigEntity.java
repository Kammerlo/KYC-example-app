package org.cardanofoundation.keriui.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "wl_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllowListConfigEntity {

    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "script_address", nullable = false)
    private String scriptAddress;

    @Column(name = "policy_id", nullable = false)
    private String policyId;

    @Column(name = "boot_tx_hash", nullable = false)
    private String bootTxHash;

    @Column(name = "boot_index", nullable = false)
    private Integer bootIndex;

    @Column(name = "te_policy_id", nullable = false)
    private String tePolicyId;
}