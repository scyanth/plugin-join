package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Exception indicating that the key depth is not reachable.
 */
public class UnreachableKeyDepthException extends RuntimeException {

    /**
     * Creates a new UnreachableKeyDepthException.
     * @param keyString Key string of the input.
     * @param sideString Stringified side of the input.
     */
    public UnreachableKeyDepthException(String keyString, String sideString) {
        super(String.format(Messages.UNREACHABLE_KEY_DEPTH, keyString, sideString));
    }
}
