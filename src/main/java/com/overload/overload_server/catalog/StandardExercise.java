package com.overload.overload_server.catalog;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "standard_exercise")
public class StandardExercise {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(nullable = false, columnDefinition = "text") private String name;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected StandardExercise() { }

    public static StandardExercise create(UUID id, String name, boolean active, Instant now) {
        var value = new StandardExercise();
        value.id = id; value.name = name; value.active = active;
        value.createdAt = now; value.updatedAt = now;
        return value;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
