package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Exception indicating that a mandatory property is blank.
 */
public class BlankPropertyException extends RuntimeException {

    /**
     * Creates a new BlankPropertyException.
     * @param propertyName Name of property.
     * @param cause Cause exception.
     */
    public BlankPropertyException(String propertyName) {
        super(String.format(Messages.BLANK_PROPERTY, propertyName));
    }
}
