package com.lifecontrol.api.common.address.repository;

import com.lifecontrol.api.common.address.model.Address;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AddressRepository extends JpaRepository<Address, UUID> {}
