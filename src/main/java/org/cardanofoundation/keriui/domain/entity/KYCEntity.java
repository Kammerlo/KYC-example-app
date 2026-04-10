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

    @Column(name = "credential_aid")
    private String credentialAid;

    @Column(name = "credential_said")
    private String credentialSaid;

    /** Hex-encoded 37-byte signed payload: user_pkh(28) || role(1) || valid_until(8). */
    @Column(name = "kyc_proof_payload", length = 74)
    private String kycProofPayload;

    /** Hex-encoded 64-byte Ed25519 signature over the payload. */
    @Column(name = "kyc_proof_signature", length = 128)
    private String kycProofSignature;

    /** Hex-encoded 32-byte Ed25519 verification key of the signing entity. */
    @Column(name = "kyc_proof_entity_vkey", length = 64)
    private String kycProofEntityVkey;

    /** TEL UTxO reference of the signing entity (txHash#index). */
    @Column(name = "kyc_proof_tel_utxo_ref", length = 128)
    private String kycProofTelUtxoRef;

    /** Valid-until timestamp in POSIX milliseconds. */
    @Column(name = "kyc_proof_valid_until")
    private Long kycProofValidUntil;
}
