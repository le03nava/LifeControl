package com.lifecontrol.api.activity.repository;

import com.lifecontrol.api.activity.model.ActivityLog;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import org.springframework.data.jpa.domain.Specification;

/**
 * Dynamic {@link Specification} predicates for filtering the activity log.
 * <p>
 * Builds predicates only for the filters that are actually present. This avoids
 * the {@code :param IS NULL} JPQL idiom, which PostgreSQL cannot type when the
 * parameter is unbound, and keeps the query index-friendly.
 */
public final class ActivityLogSpecifications {

    private ActivityLogSpecifications() {}

    public static Specification<ActivityLog> withFilters(
            LocalDateTime from, LocalDateTime to, String process, String event, String userId, String httpMethod) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (process != null) {
                predicates.add(
                        cb.equal(root.join("activityProcess", JoinType.INNER).get("name"), process));
            }
            if (event != null) {
                predicates.add(
                        cb.equal(root.join("activityEvent", JoinType.INNER).get("name"), event));
            }
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (httpMethod != null) {
                predicates.add(cb.equal(root.get("httpMethod"), httpMethod));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
