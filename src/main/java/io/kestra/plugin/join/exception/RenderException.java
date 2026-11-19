package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Wrapper of exceptions thrown in the rendering process.
 * Cause exception contains additional info.
 */
public class RenderException extends RuntimeException {

    /**
     * Creates a new RenderException.
     * @param propertyName Name of property to render.
     * @param cause Cause exception.
     */
    public RenderException(String propertyName, Throwable cause) {
        super(String.format(Messages.RENDER_ERROR, propertyName), cause);
    }
}
