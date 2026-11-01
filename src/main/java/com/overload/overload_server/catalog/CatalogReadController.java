package com.overload.overload_server.catalog;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CatalogReadController {
    private final CatalogReadService service;
    CatalogReadController(CatalogReadService service) { this.service = service; }

    @GetMapping("/api/v1/standard-exercises/search-data")
    ResponseEntity<CatalogResponses.SearchData> searchData(HttpServletRequest request) throws ServletRequestBindingException {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.searchData());
    }

    @GetMapping("/api/v1/manufacturers")
    ResponseEntity<CatalogResponses.Manufacturers> manufacturers(HttpServletRequest request) throws ServletRequestBindingException {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.manufacturers());
    }

    private static void validate(HttpServletRequest request) throws ServletRequestBindingException {
        if ((request.getQueryString() != null && !request.getQueryString().isEmpty())
                || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new ServletRequestBindingException("Catalog reads accept neither query parameters nor request bodies");
        }
    }
}
