package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Wrapper of exceptions thrown in the reading process.
 * Cause exception contains additional info.
 */
public class ReadException extends RuntimeException {

    /**
     * Creates a new ReadException.
     * @param input Stringified URI of input.
     * @param cause Cause exception.
     */
    public ReadException(String input, Throwable cause) {
        super(String.format(Messages.READ_ERROR, input), cause);
    }
}
