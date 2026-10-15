package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.SeniorityLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeniorityLevelRepository extends JpaRepository<SeniorityLevel, UUID> {

    Optional<SeniorityLevel> findByLevelCode(String levelCode);

    Optional<SeniorityLevel> findByLevelName(String levelName);

    Optional<SeniorityLevel> findByRank(Integer rank);

    boolean existsByLevelCode(String levelCode);

    boolean existsByLevelName(String levelName);

    boolean existsByRank(Integer rank);

    List<SeniorityLevel> findAllByOrderByRankAsc();

    List<SeniorityLevel> findByEnabledTrueOrderByRankAsc();
}
