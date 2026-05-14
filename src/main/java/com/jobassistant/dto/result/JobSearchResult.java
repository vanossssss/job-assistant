package com.jobassistant.dto.result;

import com.jobassistant.dto.WorkFormat;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Builder
public record JobSearchResult(
        String externalId,

        String source,
        String sourceUrl,

        String position,
        String companyName,

        String location,
        WorkFormat workFormat,

        BigDecimal salaryFrom,
        BigDecimal salaryTo,
        String currency,
        Boolean salaryGross,

        String experience,
        String employment,
        String schedule,

        List<String> skills,

        String requirements,
        String responsibilities,
        String description,

        OffsetDateTime publishedAt,
        OffsetDateTime collectedAt
) {

    public JobSearchResult {
        skills = skills == null ? List.of() : List.copyOf(skills);
        workFormat = workFormat == null ? WorkFormat.UNKNOWN : workFormat;
        collectedAt = collectedAt == null ? OffsetDateTime.now() : collectedAt;
    }

    public boolean remote() {
        return workFormat == WorkFormat.REMOTE;
    }

    public String fullTextForScoring() {
        return Stream.of(
                        position,
                        companyName,
                        location,
                        experience,
                        employment,
                        schedule,
                        workFormat != null ? workFormat.name() : null,
                        String.join(" ", skills),
                        requirements,
                        responsibilities,
                        description
                )
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .reduce("", (left, right) -> left + " " + right)
                .trim();
    }

    public boolean hasSalary() {
        return salaryFrom != null || salaryTo != null;
    }
}
