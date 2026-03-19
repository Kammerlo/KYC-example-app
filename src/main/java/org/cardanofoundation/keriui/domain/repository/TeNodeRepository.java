package org.cardanofoundation.keriui.domain.repository;

import org.cardanofoundation.keriui.domain.entity.TeNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TeNodeRepository extends JpaRepository<TeNodeEntity, String> {

    void deleteAllByTxHashIn(List<String> txHashes);
}

