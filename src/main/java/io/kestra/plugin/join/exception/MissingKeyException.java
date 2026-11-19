package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Exception indicating that key is missing from an input.
 */
public class MissingKeyException extends RuntimeException {

    /**
     * Creates a new MissingKeyException.
     * @param keyString Key string of the input.
     * @param sideString Stringified side of the input.
     * @param rowString Stringified row.
     */
    public MissingKeyException(String keyString, String sideString, String rowString) {
        super(String.format(Messages.MISSING_KEY, keyString, sideString, rowString));
    }
}
