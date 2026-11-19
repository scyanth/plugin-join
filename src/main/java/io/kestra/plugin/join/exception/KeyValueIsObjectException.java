package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Exception indicating that key value cannot be extracted because it is an object.
 */
public class KeyValueIsObjectException extends RuntimeException {

    /**
     * Creates a new KeyValueIsObjectException.
     * @param keyString Key string of the input.
     * @param sideString Stringified side of the input.
     */
    public KeyValueIsObjectException(String keyString, String sideString) {
        super(String.format(Messages.KEY_VALUE_IS_OBJECT, keyString, sideString));
    }
}
