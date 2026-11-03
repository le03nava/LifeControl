package com.lifecontrol.api.status.repository;

import com.lifecontrol.api.status.model.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StatusRepository extends JpaRepository<Status, UUID> {

    List<Status> findByStatusTypeId(UUID statusTypeId);

    Optional<Status> findByIdAndStatusTypeId(UUID id, UUID statusTypeId);

    boolean existsByStatusNameIgnoreCaseAndStatusTypeId(String statusName, UUID statusTypeId);

    @Query("""
        SELECT s FROM Status s
        JOIN FETCH s.statusType st
        WHERE LOWER(st.statusTypeName) = LOWER(:typeName)
          AND LOWER(s.statusName) = LOWER(:statusName)
        """)
    Optional<Status> findByTypeNameAndStatusName(
            @Param("typeName") String typeName, @Param("statusName") String statusName);

    /**
     * One catalog row with its family, by id: the read every document family needs to turn a request's
     * {@code statusId} into a state through {@link Status#getCode()} (ADR-0004 decision 7).
     *
     * <p>The {@code JOIN FETCH} is the point of the finder and not an optimization: a caller outside a
     * transaction that touched {@code getStatusType()} on a lazily loaded row would answer with an
     * {@code IllegalStateException} about a closed session, and a refusal about a session is not a refusal
     * about the caller's status id. Every reader needs the family name to tell "a status of another family"
     * from "a status this family cannot become", so the family travels with the row.</p>
     */
    @Query("""
        SELECT s FROM Status s
        JOIN FETCH s.statusType
        WHERE s.id = :id
        """)
    Optional<Status> findWithStatusTypeById(@Param("id") UUID id);
}
