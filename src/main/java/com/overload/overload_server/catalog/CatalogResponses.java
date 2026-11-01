package com.overload.overload_server.catalog;

import java.util.*;

public final class CatalogResponses {
    private CatalogResponses() { }
    public record SearchData(int schemaVersion, List<Exercise> exercises) { }
    public record Exercise(UUID id, String name, List<String> aliases) { }
    public record Manufacturers(List<ManufacturerItem> manufacturers) { }
    public record ManufacturerItem(UUID id, String name) { }
}
