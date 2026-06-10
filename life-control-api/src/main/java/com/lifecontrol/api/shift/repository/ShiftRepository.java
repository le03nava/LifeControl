package com.lifecontrol.api.shift.repository;

import com.lifecontrol.api.shift.model.Shift;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    List<Shift> findByCompanyStoreId(UUID companyStoreId);

    List<Shift> findByStatus(String status);

    Page<Shift> findByEnabledTrueOrderByOpenedAtDesc(Pageable pageable);

    @Query("""
        SELECT s FROM Shift s
        WHERE s.companyStoreId = :storeId
          AND s.status = 'ABIERTO'
          AND s.enabled = true
        """)
    Optional<Shift> findOpenShiftByStoreId(@Param("storeId") UUID storeId);

    @Query("""
        SELECT s FROM Shift s
        WHERE s.userId = :userId
          AND s.status = 'ABIERTO'
          AND s.enabled = true
        ORDER BY s.openedAt DESC
        """)
    List<Shift> findOpenShiftByUserId(@Param("userId") String userId);

    @Query("""
        SELECT s FROM Shift s
        WHERE s.status = 'ABIERTO'
          AND s.enabled = true
        ORDER BY s.openedAt DESC
        """)
    List<Shift> findAllOpenShifts();
}
