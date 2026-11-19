package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Exception indicating an impossible sort between two values that have different types.
 */
public class DifferentTypesException extends RuntimeException {

    /**
     * Creates a new DifferentTypesException.
     * @param va Stringified first value.
     * @param vb Stringified second value.
     */
    public DifferentTypesException(String va, String vb) {
        super(String.format(Messages.DIFFERENT_TYPES, va, vb));
    }
}
