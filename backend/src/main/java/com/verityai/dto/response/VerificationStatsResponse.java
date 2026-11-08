package com.verityai.dto.response;

import lombok.Data;

@Data
public class VerificationStatsResponse {

    private long total;

    /** Messages with at least one stored verification row. */
    private long verified;

    /** Messages where some analyzed claims were not classified. */
    private long partial;

    /** Messages whose analyzed claims were all classified. */
    private long completed;

    /** Messages with no stored verification. */
    private long unverified;
}
