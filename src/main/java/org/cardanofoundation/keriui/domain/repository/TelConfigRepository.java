package org.cardanofoundation.keriui.domain.repository;

import org.cardanofoundation.keriui.domain.entity.TelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TelConfigRepository extends JpaRepository<TelConfigEntity, Integer> {
}
