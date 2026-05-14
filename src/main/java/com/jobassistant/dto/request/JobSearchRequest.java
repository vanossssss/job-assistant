package com.jobassistant.dto.request;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record JobSearchRequest(String query,
                               String location,
                               Boolean remoteOnly,
                               BigDecimal salaryFrom,
                               Integer page,
                               Integer perPage) {
}
