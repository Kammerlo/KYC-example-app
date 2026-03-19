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

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "first_name", length = 255)
    private String firstName;

    @Column(name = "last_name", length = 255)
    private String lastName;

    @Column(name = "cardano_address", length = 255)
    private String cardanoAddress;
}
