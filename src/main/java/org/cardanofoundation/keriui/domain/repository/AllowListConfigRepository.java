package org.cardanofoundation.keriui.domain.repository;

import org.cardanofoundation.keriui.domain.entity.AllowListConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AllowListConfigRepository extends JpaRepository<AllowListConfigEntity, Integer> {}