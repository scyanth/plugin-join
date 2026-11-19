package io.kestra.plugin.join.exception;

import io.kestra.plugin.join.util.Messages;

/**
 * Wrapper of exceptions thrown in the storage process.
 * Cause exception contains additional info.
 */
public class StorageException extends RuntimeException {

    /**
     * Creates a new StorageException.
     * @param cause Cause exception.
     */
    public StorageException(Throwable cause) {
        super(Messages.STORAGE_ERROR, cause);
    }
}
