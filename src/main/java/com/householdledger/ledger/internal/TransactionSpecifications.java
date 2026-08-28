package com.householdledger.ledger.internal;

import com.householdledger.ledger.domain.TransactionFilter;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.UUID;

/**
 * Dynamically composed query criteria for {@link TransactionEntity}
 * (PRD §FR-5).
 *
 * <p>PRD §6.4 is explicit about the approach: "Filtering is implemented with
 * Spring Data JPA <b>Specifications</b>, composed dynamically. Raw Criteria
 * API is avoided — it is verbose, hard to read, and offers no advantage
 * here." Each criterion below is an independent, named {@code Specification}
 * that reads as the rule it expresses; the service composes only the ones
 * the caller actually supplied.
 *
 * <p>The household predicate is not optional and is not derived from the
 * filter — it is always applied by {@link #matching}, from the caller's
 * verified household id. That makes cross-household leakage a structural
 * impossibility rather than something each query has to remember (PRD §FR-1,
 * §9): there is no code path here that builds a query without it.
 */
final class TransactionSpecifications {

    private TransactionSpecifications() {
        // Specification factory holder.
    }

    /**
     * Composes the full query: mandatory household scoping, plus whichever
     * of the FR-5 criteria the caller supplied.
     */
    static Specification<TransactionEntity> matching(UUID householdId, TransactionFilter filter) {
        Specification<TransactionEntity> spec = inHousehold(householdId);

        if (filter.from() != null) {
            spec = spec.and(occurredOnOrAfter(filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.and(occurredOnOrBefore(filter.to()));
        }
        if (filter.memberId() != null) {
            spec = spec.and(createdBy(filter.memberId()));
        }
        if (filter.descriptionContains() != null) {
            spec = spec.and(descriptionContains(filter.descriptionContains()));
        }
        if (filter.accountId() != null) {
            spec = spec.and(hasPostingToAccount(filter.accountId()));
        }

        return spec;
    }

    static Specification<TransactionEntity> inHousehold(UUID householdId) {
        return (root, query, cb) -> cb.equal(root.get("householdId"), householdId);
    }

    /** Range bounds are inclusive at both ends, matching {@code TransactionFilter.includesDate}. */
    static Specification<TransactionEntity> occurredOnOrAfter(java.time.LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredOn"), from);
    }

    static Specification<TransactionEntity> occurredOnOrBefore(java.time.LocalDate to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredOn"), to);
    }

    static Specification<TransactionEntity> createdBy(UUID memberId) {
        return (root, query, cb) -> cb.equal(root.get("createdBy"), memberId);
    }

    /**
     * Case-insensitive substring match on the description (PRD §FR-5's
     * "free-text description match").
     *
     * <p>Both sides are lowered rather than relying on the database's
     * collation, so behaviour is identical regardless of how the deployment's
     * Postgres is configured. Wildcards in the user's term are escaped: a
     * search for "50%" should look for the literal text, not match everything
     * from a stray {@code %}.
     */
    static Specification<TransactionEntity> descriptionContains(String term) {
        String pattern = "%" + escapeLikeWildcards(term.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("description")), pattern, '\\');
    }

    /**
     * Matches transactions having at least one posting against the given
     * account (PRD §FR-5's "filtered by ... account").
     *
     * <p>An {@code EXISTS} subquery rather than a join, for two reasons: a
     * join would multiply the transaction row once per matching posting and
     * need a {@code DISTINCT} to undo it (which also breaks the count query),
     * and {@code TransactionEntity} deliberately has no mapped postings
     * collection — postings are written one row at a time so the deferred
     * balance trigger sees the complete set at commit (PRD §6.3), and adding
     * a cascade-managed collection now would disturb that. The subquery joins
     * on the plain {@code transactionId} column instead.
     */
    static Specification<TransactionEntity> hasPostingToAccount(UUID accountId) {
        return (root, query, cb) -> {
            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<PostingEntity> posting = subquery.from(PostingEntity.class);

            subquery.select(posting.get("id"))
                    .where(cb.and(
                            cb.equal(posting.get("transactionId"), root.get("id")),
                            cb.equal(posting.get("accountId"), accountId)));

            return cb.exists(subquery);
        };
    }

    /**
     * Escapes the LIKE metacharacters so a user's literal {@code %}, {@code _}
     * or {@code \} is searched for rather than interpreted. Paired with the
     * {@code '\\'} escape character passed to {@code cb.like} above.
     */
    private static String escapeLikeWildcards(String term) {
        return term.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
