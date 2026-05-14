package com.jobassistant.provider;

import com.jobassistant.dto.request.JobSearchRequest;
import com.jobassistant.dto.result.JobSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobSearchFacade {

    private final List<JobSearchProvider> providers;

    public Map<String, List<JobSearchResult>> searchAll(JobSearchRequest request) {
        Map<String, List<JobSearchResult>> results = new LinkedHashMap<>();

        validateRequest(request);

        for (JobSearchProvider provider : providers) {
            String providerName = provider.getProviderName();

            try {
                log.info("Поиск вакансий через провайдера {}...", providerName);

                List<JobSearchResult> found = provider.search(request);

                if (found == null) {
                    found = List.of();
                }

                results.put(providerName, found);

                log.info("{}: найдено {} вакансий", providerName, found.size());

            } catch (Exception e) {
                log.error("Ошибка поиска через провайдера {}: {}", providerName, e.getMessage(), e);

                results.put(providerName, List.of());
            }
        }

        return results;
    }

    public List<JobSearchResult> searchByProvider(String providerName, JobSearchRequest request) {
        validateRequest(request);

        JobSearchProvider provider = findProviderByName(providerName);

        try {
            log.info("Поиск вакансий через провайдера {}...", provider.getProviderName());

            List<JobSearchResult> found = provider.search(request);

            if (found == null) {
                return List.of();
            }

            log.info("{}: найдено {} вакансий", provider.getProviderName(), found.size());

            return found;

        } catch (Exception e) {
            log.error("Ошибка поиска через провайдера {}: {}", provider.getProviderName(), e.getMessage(), e);
            return List.of();
        }
    }

    public List<String> getProviders() {
        return providers.stream()
                .map(JobSearchProvider::getProviderName)
                .collect(Collectors.toList());
    }

    public List<String> getAvailableProviders() {
        return providers.stream()
                .filter(this::safeIsAvailable)
                .map(JobSearchProvider::getProviderName)
                .collect(Collectors.toList());
    }

    public List<JobSearchResult> searchAllAsList(JobSearchRequest request) {
        return searchAll(request).values().stream()
                .flatMap(List::stream)
                .toList();
    }

    private JobSearchProvider findProviderByName(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("Название провайдера не должно быть пустым");
        }

        return providers.stream()
                .filter(provider -> provider.getProviderName().equalsIgnoreCase(providerName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Провайдер не найден: " + providerName));
    }

    private boolean safeIsAvailable(JobSearchProvider provider) {
        try {
            return provider.isAvailable();
        } catch (Exception e) {
            log.warn("Не удалось проверить доступность провайдера {}: {}",
                    provider.getProviderName(),
                    e.getMessage()
            );
            return false;
        }
    }

    private void validateRequest(JobSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Параметры поиска не должны быть null");
        }

        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("Поисковый запрос не должен быть пустым");
        }
    }
}