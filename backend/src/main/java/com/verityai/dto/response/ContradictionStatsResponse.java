package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Contradiction totals for the Verification page. Every approved class is
 * present in the map even when its count is zero.
 */
@Data
public class ContradictionStatsResponse {

    private long total;
    private Map<String, Long> byType;
    private Map<String, Long> bySeverity;
    private Instant lastDetectedAt;
}
