package com.seatflow.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

/**
 * A grantable role. Rows are seeded by migration V1 and never created at runtime,
 * so there is no generator on the identifier.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private RoleName name;

    protected Role() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public RoleName getName() {
        return name;
    }

    public String authority() {
        return name.authority();
    }

    /**
     * Identity is the persistent key. Roles are always loaded from the database,
     * never constructed transiently, so a null id would itself be a bug.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Role role)) {
            return false;
        }
        return id != null && id.equals(role.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Role[" + name + "]";
    }
}
