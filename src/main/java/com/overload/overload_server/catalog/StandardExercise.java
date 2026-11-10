package com.overload.overload_server.catalog;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "standard_exercise")
public class StandardExercise {
    public enum JointType { COMPOUND, ISOLATION, OTHER }
    public enum ExerciseType { WEIGHT, CARDIO, STATIC_HOLD, STRETCH, OTHER }
    public enum RecordMethod { SETS_REPS, CARDIO, HOLD_DURATION, UNSPECIFIED }
    public enum PplSplit { PUSH, PULL, LEGS }
    public enum FrontBackSplit { FRONT, BACK }

    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(nullable = false, columnDefinition = "text") private String name;
    @Enumerated(EnumType.STRING) @Column(name = "joint_type", columnDefinition = "text") private JointType jointType;
    @Enumerated(EnumType.STRING) @Column(name = "exercise_type", nullable = false, columnDefinition = "text")
    private ExerciseType exerciseType;
    @Enumerated(EnumType.STRING) @Column(name = "record_method", nullable = false, columnDefinition = "text")
    private RecordMethod recordMethod;
    @Enumerated(EnumType.STRING) @Column(name = "ppl_split", columnDefinition = "text") private PplSplit pplSplit;
    @Enumerated(EnumType.STRING) @Column(name = "front_back_split", columnDefinition = "text") private FrontBackSplit frontBackSplit;
    @Column(name = "instructions_ko", columnDefinition = "text") private String instructionsKo;
    @Column(name = "equipment_description", columnDefinition = "text") private String equipmentDescription;
    @Column(name = "cardio_activity_id") private UUID cardioActivityId;
    @Column(name = "representative_gif_key", columnDefinition = "text") private String representativeGifKey;
    @Column(name = "muscle_targets_reviewed", nullable = false) private boolean muscleTargetsReviewed;
    @Column(name = "splits_reviewed", nullable = false) private boolean splitsReviewed;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected StandardExercise() { }

    public static StandardExercise create(UUID id, String name, boolean active, Instant now) {
        return create(id, name, null, active, now);
    }

    public static StandardExercise create(UUID id, String name, JointType jointType, boolean active, Instant now) {
        var value = new StandardExercise();
        value.id = id; value.active = active;
        value.updateDetails(name, jointType, ExerciseType.WEIGHT, RecordMethod.SETS_REPS, null, null, null, now);
        value.createdAt = now; value.updatedAt = now;
        return value;
    }

    public void updateDetails(String name, JointType jointType, ExerciseType exerciseType, RecordMethod recordMethod,
                              String instructionsKo, String equipmentDescription, String representativeGifKey, Instant now) {
        requireNonBlank(Objects.requireNonNull(name));
        requireNonBlank(instructionsKo);
        requireNonBlank(equipmentDescription);
        requireNonBlank(representativeGifKey);
        this.exerciseType = Objects.requireNonNull(exerciseType);
        Objects.requireNonNull(recordMethod);
        if (cardioActivityId != null && recordMethod != RecordMethod.CARDIO) {
            throw new IllegalArgumentException("Remove the cardio activity link before changing record method");
        }
        this.recordMethod = recordMethod;
        this.name = name;
        this.jointType = jointType;
        this.instructionsKo = instructionsKo;
        this.equipmentDescription = equipmentDescription;
        this.representativeGifKey = representativeGifKey;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void updateSplits(PplSplit pplSplit, FrontBackSplit frontBackSplit, Instant now) {
        this.pplSplit = pplSplit;
        this.frontBackSplit = frontBackSplit;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void reviewMuscleTargets(boolean reviewed, Instant now) {
        this.muscleTargetsReviewed = reviewed;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void reviewSplits(boolean reviewed, Instant now) {
        this.splitsReviewed = reviewed;
        this.updatedAt = Objects.requireNonNull(now);
    }

    private static void requireNonBlank(String value) {
        if (value != null && value.isBlank()) throw new IllegalArgumentException("Blank standard exercise value");
    }

    public void linkCardioActivity(UUID activityId, Instant now) {
        if (activityId != null && recordMethod != RecordMethod.CARDIO) {
            throw new IllegalArgumentException("Cardio record method required");
        }
        this.updatedAt = Objects.requireNonNull(now);
        this.cardioActivityId = activityId;
    }

    public boolean isPublished() {
        return active && muscleTargetsReviewed && splitsReviewed;
    }

    public boolean isSelectableForSetsReps() {
        return isPublished() && recordMethod == RecordMethod.SETS_REPS;
    }

    public ExerciseType getExerciseType() { return exerciseType; }
    public RecordMethod getRecordMethod() { return recordMethod; }
    public PplSplit getPplSplit() { return pplSplit; }
    public FrontBackSplit getFrontBackSplit() { return frontBackSplit; }
    public String getInstructionsKo() { return instructionsKo; }
    public String getEquipmentDescription() { return equipmentDescription; }
    public UUID getCardioActivityId() { return cardioActivityId; }
    public String getRepresentativeGifKey() { return representativeGifKey; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isMuscleTargetsReviewed() { return muscleTargetsReviewed; }
    public boolean isSplitsReviewed() { return splitsReviewed; }

    public UUID getId() { return id; }
    public String getName() { return name; }
    /** NULL is unclassified, not OTHER; callers must not invent a classification. */
    public JointType getJointType() { return jointType; }
    public boolean isActive() { return active; }
}
