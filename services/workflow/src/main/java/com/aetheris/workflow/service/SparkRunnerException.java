package com.aetheris.workflow.service;

/** Safe failure returned by the managed Spark control plane. */
public class SparkRunnerException extends RuntimeException {

    private final int statusCode;
    private final boolean submissionOutcomeUnknown;

    public SparkRunnerException(String message, int statusCode, boolean submissionOutcomeUnknown) {
        super(message);
        this.statusCode = statusCode;
        this.submissionOutcomeUnknown = submissionOutcomeUnknown;
    }

    public int getStatusCode() { return statusCode; }
    public boolean isSubmissionOutcomeUnknown() { return submissionOutcomeUnknown; }
}
