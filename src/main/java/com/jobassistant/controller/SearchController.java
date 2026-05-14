package com.jobassistant.controller;

import com.jobassistant.dto.request.JobSearchRequest;
import com.jobassistant.dto.result.JobSearchResult;
import com.jobassistant.provider.JobSearchFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class SearchController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PER_PAGE = 20;
    private static final int MAX_PER_PAGE = 50;

    private final JobSearchFacade jobSearchFacade;

    @GetMapping("/search")
    public ResponseEntity<Map<String, List<JobSearchResult>>> search(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "perPage", required = false, defaultValue = "20") int perPage
    ) {
        String normalizedQuery = normalize(query);

        if (normalizedQuery.isBlank()) {
            return ResponseEntity.badRequest().body(new LinkedHashMap<>());
        }

        JobSearchRequest request = JobSearchRequest.builder()
                .query(normalizedQuery)
                .location(normalize(location))
                .page(normalizePage(page))
                .perPage(normalizePerPage(perPage))
                .build();

        try {
            Map<String, List<JobSearchResult>> results = jobSearchFacade.searchAll(request);
            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("Ошибка поиска вакансий: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new LinkedHashMap<>());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private int normalizePage(int page) {
        return Math.max(page, DEFAULT_PAGE);
    }

    private int normalizePerPage(int perPage) {
        if (perPage <= 0) {
            return DEFAULT_PER_PAGE;
        }

        return Math.min(perPage, MAX_PER_PAGE);
    }
}
