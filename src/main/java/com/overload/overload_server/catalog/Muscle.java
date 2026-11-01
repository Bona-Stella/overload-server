package com.overload.overload_server.catalog;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "muscle")
public class Muscle {
    public enum Group { CHEST, BACK, SHOULDERS, ARMS, LEGS, CORE }

    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Enumerated(EnumType.STRING) @Column(name = "group_code", nullable = false, columnDefinition = "text") private Group group;
    @Column(nullable = false, columnDefinition = "text") private String name;
    @Column(name = "is_active", nullable = false) private boolean active;

    protected Muscle() { }

    public static Muscle create(UUID id, Group group, String name, boolean active) {
        var value = new Muscle();
        value.id = id; value.group = group; value.name = name; value.active = active;
        return value;
    }

    public UUID getId() { return id; }
    public Group getGroup() { return group; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
