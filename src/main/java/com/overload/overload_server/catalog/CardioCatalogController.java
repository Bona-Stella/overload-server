package com.overload.overload_server.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
public class CardioCatalogController {
    private final JdbcTemplate jdbc;
    public CardioCatalogController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Activity(UUID id,String category,String name,String kind) { }
    @GetMapping("/api/v1/cardio-activities")
    public ResponseEntity<Map<String,List<Activity>>> list(HttpServletRequest request) throws org.springframework.web.bind.ServletRequestBindingException {
        if(request.getQueryString()!=null||request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new org.springframework.web.bind.ServletRequestBindingException("Read accepts no query or body");
        var rows=jdbc.query("SELECT id,category,name,kind FROM cardio_activity_catalog WHERE active ORDER BY category,id",(r,i)->new Activity(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("activities",rows));
    }
}
