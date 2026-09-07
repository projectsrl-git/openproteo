package com.legalarchive.orchestrator.ftps;

/** The mask list and the directory do not agree, and the step must not start. */
public class PlanException extends Exception {

    private static final long serialVersionUID = 1L;

    public PlanException(String message) {
        super(message);
    }
}
