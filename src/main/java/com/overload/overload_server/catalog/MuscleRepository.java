package com.overload.overload_server.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface MuscleRepository extends JpaRepository<Muscle, UUID> { }
