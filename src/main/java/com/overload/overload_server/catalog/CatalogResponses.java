package com.overload.overload_server.catalog;

import java.util.*;

public final class CatalogResponses {
    private CatalogResponses() { }
    public record SearchData(int schemaVersion, List<Exercise> exercises) { }
    public record MuscleItem(UUID id,String groupCode,String name,String role) { }
    public record Exercise(UUID id, String name, List<String> aliases,List<MuscleItem> muscles) { public Exercise(UUID id,String name,List<String> aliases){this(id,name,aliases,List.of());} }
    public record Manufacturers(List<ManufacturerItem> manufacturers) { }
    public record ManufacturerItem(UUID id, String name) { }
}
