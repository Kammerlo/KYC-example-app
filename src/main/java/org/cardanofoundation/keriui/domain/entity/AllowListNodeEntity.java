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
@Table(name = "allow_list_node")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AllowListNodeEntity {
    
    @Id
    @Column(name = "node_policy_id", nullable =  false)
    private String nodePolicyId;

    @Column(name = "tx_hash", nullable = false)
    private String txHash;

    @Column(name = "output_index", nullable = false)
    private Integer outputIndex;

    @Column(name = "pkh", nullable = false)
    private String pkh;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "role", nullable = false)
    private Integer role;

}
