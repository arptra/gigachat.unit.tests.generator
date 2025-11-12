package com.gigachat.unit.tests.generator.pipeline;

/**
 * Exception thrown when the LLM returns an invalid snippet that reimplements the target method.
 */
public class InvalidLLMResponseException extends RuntimeException {
    public InvalidLLMResponseException(String message) {
        super(message);
    }

    public InvalidLLMResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
