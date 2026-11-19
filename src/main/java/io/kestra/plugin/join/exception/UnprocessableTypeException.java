package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Exception indicating a ION type that is not processable.
 */
public class UnprocessableTypeException extends RuntimeException {

    /**
     * Creates a new UnprocessableTypeException for two compared values.
     */
    public UnprocessableTypeException() {
        super(Messages.UNPROCESSABLE_COMPARED_TYPES);
    }

    /**
     * Creates a new UnprocessableTypeException for a key value.
     * @param keyString Key string of the input.
     * @param sideString Stringified side of the input.
     */
    public UnprocessableTypeException(String keyString, String sideString) {
        super(String.format(Messages.UNPROCESSABLE_KEY_TYPE, keyString, sideString));
    }
}
