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

    /**
     * One catalog row by the family it belongs to and the {@code code} it spells: the read every document
     * response projects its {@code statusId}/{@code statusName} pair from (ADR-0004 decisions 1 and 7).
     *
     * <p><b>Why a code and not an id.</b> The document stores the enum constant and nothing else once
     * {@code V71} drops {@code status_id}, so the only key a reader has is the code. This finder is the
     * inverse of {@link com.lifecontrol.api.common.state.DocumentStateCatalog}'s: that one turns a request's
     * {@code statusId} into a constant, and this one turns a constant back into the row a screen renders.
     * The pair is therefore a <b>projection of the code</b>, which is why a rename in the catalog changes a
     * label and not a behavior.</p>
     *
     * <p><b>{@code status_type_name} is part of the key and not context.</b> {@code statuses} is one table for
     * seven families and {@code PENDING}, {@code RECEIVED} and {@code CANCELLED} are codes two or more
     * families share, so a lookup by code alone would hand a purchase-order line the sales order's label.
     * The {@code JOIN FETCH} carries the family on the row, so a caller that names the family in a refusal can
     * read it without a second query and without a lazy load outside a transaction.</p>
     */
    @Query("""
        SELECT s FROM Status s
        JOIN FETCH s.statusType st
        WHERE st.statusTypeName = :typeName
          AND s.code = :code
        """)
    Optional<Status> findByTypeNameAndCode(@Param("typeName") String typeName, @Param("code") String code);
}
