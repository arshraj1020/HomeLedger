package com.householdledger.identity.internal;

import com.householdledger.identity.domain.Household;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping for the {@code household} table (PRD §6.3). Package-private by design. */
@Entity
@Table(name = "household")
class HouseholdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /**
     * Mapped as SQL {@code CHAR}, not Hibernate's default {@code VARCHAR}.
     *
     * <p>PRD §6.3 specifies {@code currency CHAR(3) NOT NULL DEFAULT 'INR'}
     * and the V1 baseline migration implements exactly that. V1 is applied
     * and in version control, so the schema is authoritative here and the
     * mapping is what must agree with it — not the reverse. Without this
     * annotation Hibernate expects {@code varchar(3)}, and
     * {@code ddl-auto: validate} rejects PostgreSQL's {@code bpchar} at
     * startup.
     *
     * <p>Note that {@code CHAR(n)} is blank-padded on read. Harmless here
     * because v1 is INR-only (PRD §3.3) and "INR" is exactly three
     * characters; a shorter code would come back space-padded and want
     * trimming at that point.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HouseholdEntity() {
        // JPA
    }

    HouseholdEntity(UUID id, String name, String currency) {
        this.id = id;
        this.name = name;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getCurrency() {
        return currency;
    }

    Household toDomain() {
        return new Household(id, name, currency);
    }
}
