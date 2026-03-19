package org.cardanofoundation.keriui.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "te_node")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TeNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "te_node_seq")
    @SequenceGenerator(name = "te_node_seq_gen", sequenceName = "te_node_seq", allocationSize = 1)
    private Long id;

    @Column(name = "node_policy_id", nullable =  false)
    private String nodePolicyId;

    @Column(name = "tx_hash", nullable = false)
    private String txHash;

    @Column(name = "output_index", nullable = false)
    private Integer outputIndex;

    @Column(name = "vkey", nullable = false)
    private String vkey;

}
