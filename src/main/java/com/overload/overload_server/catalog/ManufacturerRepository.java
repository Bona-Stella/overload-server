package com.overload.overload_server.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, UUID> {
    List<Manufacturer> findAllByActiveTrueOrderByIdAsc();
}
