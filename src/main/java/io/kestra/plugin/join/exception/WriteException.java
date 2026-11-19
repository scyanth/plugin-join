package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;
import io.kestra.plugin.join.util.Side;

/**
 * Wrapper of exceptions thrown in the writing process.
 * Cause exception contains additional info.
 */
public class WriteException extends RuntimeException {

    /**
     * Creates a new WriteException for a merged row.
     * @param leftRowString Stringified row from left input.
     * @param rightRowString Stringified row from right input.
     * @param cause Cause exception.
     */
    public WriteException(String leftRowString, String rightRowString, Throwable cause) {
        super(String.format(Messages.WRITE_ERROR,
            String.format("a merged row between %s and %s", leftRowString, rightRowString)
        ), cause);
    }

    /**
     * Creates a new WriteException for a side row.
     * @param rowString Stringified row from input.
     * @param side Side of the input.
     * @param cause Cause exception.
     */
    public WriteException(String rowString, Side side, Throwable cause) {
        super(String.format(Messages.WRITE_ERROR,
            String.format("%s from %s side", rowString, side.toString())
        ), cause);
    }
}
