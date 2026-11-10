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

    @Enumerated(EnumType.STRING) @Column(name = "default_ppl_split", columnDefinition = "text")
    private StandardExercise.PplSplit defaultPplSplit;
    @Enumerated(EnumType.STRING) @Column(name = "default_front_back_split", columnDefinition = "text")
    private StandardExercise.FrontBackSplit defaultFrontBackSplit;

    protected Muscle() { }

    public void updateDefaultSplits(StandardExercise.PplSplit pplSplit, StandardExercise.FrontBackSplit frontBackSplit) {
        this.defaultPplSplit = pplSplit;
        this.defaultFrontBackSplit = frontBackSplit;
    }

    public StandardExercise.PplSplit getDefaultPplSplit() { return defaultPplSplit; }
    public StandardExercise.FrontBackSplit getDefaultFrontBackSplit() { return defaultFrontBackSplit; }

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
