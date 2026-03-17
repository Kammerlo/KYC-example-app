package org.cardanofoundation.keriui.domain.repository;

import org.cardanofoundation.keriui.domain.entity.KYCEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KycRepository extends JpaRepository<KYCEntity, String> {
    // Additional query methods can be added here if needed
}

