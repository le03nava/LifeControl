package com.lifecontrol.api.measureunit.repository;

import com.lifecontrol.api.measureunit.model.MeasureUnit;
import com.lifecontrol.api.measureunit.model.MeasureUnitType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MeasureUnitRepository extends JpaRepository<MeasureUnit, UUID> {

    boolean existsBySatCode(String satCode);

    boolean existsBySatCodeAndIdNot(String satCode, UUID id);

    List<MeasureUnit> findByEnabledTrue();

    List<MeasureUnit> findByUnitTypeAndEnabledTrue(MeasureUnitType unitType);

    Optional<MeasureUnit> findBySatCode(String satCode);
}
