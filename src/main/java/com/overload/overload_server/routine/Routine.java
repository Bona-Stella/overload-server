package com.overload.overload_server.routine;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "routines")
public class Routine {

	@Id UUID id;
	@Column(name = "user_id", nullable = false, updatable = false) UUID userId;
	@Column(name = "data_generation", nullable = false, updatable = false) long dataGeneration;
	@Column(nullable = false, columnDefinition = "text") String name;
	@Column(columnDefinition = "text") String tag;
	// One counter owner: the service increments under FOR UPDATE, not @Version plus a second increment.
	@Column(nullable = false) long version;
	@Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
	@Column(name = "updated_at", nullable = false) Instant updatedAt;
	@Column(name = "deleted_at") Instant deletedAt;

	protected Routine() { }

	static Routine create(UUID id, UUID userId, long generation, String name, Instant now) {
		var value = new Routine();
		value.id = id; value.userId = userId; value.dataGeneration = generation; value.name = name;
		value.createdAt = now; value.updatedAt = now;
		return value;
	}

	void changed(Instant now) {
		if (version == Long.MAX_VALUE) throw new RoutineFailure(RoutineFailure.Reason.WRITE_CONFLICT);
		version++; updatedAt = now;
	}
}
