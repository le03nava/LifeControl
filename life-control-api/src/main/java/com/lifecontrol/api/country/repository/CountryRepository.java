package com.lifecontrol.api.country.repository;

import com.lifecontrol.api.country.model.Country;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CountryRepository extends JpaRepository<Country, UUID> {

    Optional<Country> findByCountryCode(String countryCode);

    boolean existsByCountryCode(String countryCode);

    List<Country> findByEnabledTrue();
}
