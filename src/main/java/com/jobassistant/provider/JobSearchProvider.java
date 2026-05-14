package com.jobassistant.provider;

import com.jobassistant.dto.request.JobSearchRequest;
import com.jobassistant.dto.result.JobSearchResult;

import java.util.List;

public interface JobSearchProvider {

    String getProviderName();

    List<JobSearchResult> search(JobSearchRequest searchRequest);

    boolean isAvailable();

}
