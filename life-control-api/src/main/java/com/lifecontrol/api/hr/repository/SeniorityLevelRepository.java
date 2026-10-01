package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.SeniorityLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeniorityLevelRepository extends JpaRepository<SeniorityLevel, UUID> {

    Optional<SeniorityLevel> findByLevelCodeIgnoreCase(String levelCode);

    Optional<SeniorityLevel> findByLevelNameIgnoreCase(String levelName);

    Optional<SeniorityLevel> findByRank(Integer rank);

    boolean existsByLevelCodeIgnoreCase(String levelCode);

    boolean existsByLevelNameIgnoreCase(String levelName);

    boolean existsByRank(Integer rank);

    List<SeniorityLevel> findAllByOrderByRankAsc();

    List<SeniorityLevel> findByEnabledTrueOrderByRankAsc();
}
