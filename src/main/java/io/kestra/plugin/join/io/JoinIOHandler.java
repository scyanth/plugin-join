package io.kestra.plugin.join.io;

import io.kestra.plugin.join.util.Messages;
import io.kestra.plugin.join.util.Side;
import io.kestra.plugin.join.util.OutputMode;
import io.kestra.plugin.join.util.JoinState;
import io.kestra.plugin.join.exception.ReadException;
import io.kestra.plugin.join.exception.WriteException;
import io.kestra.plugin.join.exception.StorageException;

import io.kestra.core.runners.RunContext;

import com.amazon.ion.IonSystem;
import com.amazon.ion.IonType;
import com.amazon.ion.IonValue;
import com.amazon.ion.IonWriter;
import com.amazon.ion.system.IonSystemBuilder;
import com.amazon.ion.system.IonTextWriterBuilder;
import com.amazon.ion.IonStruct;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.HashSet;

import org.slf4j.Logger;

/**
 * Layer to handle I/O operations with Kestra run context and ION formatting.
 * Holds open input and output streams: always close it, e.g. with try-with-resources.
 */
public class JoinIOHandler implements AutoCloseable {

    /** Kestra run context. */
    private RunContext kestraContext;

    /** The Kestra logger (slf4j) for the task. */
    private Logger logger;

    /** URI of left input file. */
    private URI leftUri;

    /** URI of right input file. */
    private URI rightUri;

    /** URI of output file. */
    private URI outputUri;

    /** Output mode. */
    private OutputMode outputMode;

    /** Label for left fields in output. */
    private String leftLabel;

    /** Label for right fields in output */
    private String rightLabel;

    /** Separator character for prefixes */
    private String prefixSeparator;

    /** Count of output rows. */
    private long outputSize;

    /** Count of left rows. */
    private long leftSize;

    /** Count of right rows. */
    private long rightSize;

    /** ION system to handle the format. */
    private IonSystem ionSystem;

    /** ION reader for left input. */
    private IterableIonReader leftIonReader;

    /** ION reader for right input. */
    private IterableIonReader rightIonReader;

    /** ION writer for output. */
    private IonWriter ionWriter;

    /** Temporary output file in the task working directory. */
    private Path outputFile;

    /** Data stream of output file. */
    private OutputStream outputStream;

    /** State of task. */
    private JoinState state;

    /**
     * Creates a new JoinIOHandler.
     * Opens both inputs and a temporary output file; everything already opened is closed if a later step fails.
     * @param kestraContext Kestra run context.
     * @param leftUri URI of left input file.
     * @param rightUri URI of right input file.
     * @param leftLabel Label for left fields in output.
     * @param rightLabel Label for right fields in output.
     * @param prefixSeparator Separator character for prefixes.
     * @param outputMode Output mode.
     */
    public JoinIOHandler(
        RunContext kestraContext,
        URI leftUri,
        URI rightUri,
        String leftLabel,
        String rightLabel,
        String prefixSeparator,
        OutputMode outputMode
    ) {

        this.kestraContext = kestraContext;
        this.leftUri = leftUri;
        this.rightUri = rightUri;
        this.outputMode = outputMode;
        this.leftLabel = leftLabel;
        this.rightLabel = rightLabel;
        this.prefixSeparator = prefixSeparator;

        logger = kestraContext.logger();

        outputSize = 0;

        state = JoinState.SUCCESS;

        ionSystem = IonSystemBuilder.standard().build();

        leftSize = countInputRows(Side.LEFT);
        rightSize = countInputRows(Side.RIGHT);

        try {
            leftIonReader = createInputReader(leftUri);
            rightIonReader = createInputReader(rightUri);

            try {
                outputFile = kestraContext.workingDir().createTempFile(".ion");
                outputStream = new BufferedOutputStream(Files.newOutputStream(outputFile));
            } catch (IOException e) {
                throw new StorageException(e);
            }

            // One row per line, without patching the text afterwards (string values may contain "} {").
            ionWriter = IonTextWriterBuilder.standard()
                .withCharset(IonTextWriterBuilder.UTF8)
                .withWriteTopLevelValuesOnNewLines(true)
                .withNewLineType(IonTextWriterBuilder.NewLineType.LF)
                .build(outputStream);
        } catch (RuntimeException e) {
            try {
                close();
            } catch (Exception closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }

    /**
     * Get the ION reader of an input.
     * @param side Side of input.
     * @return ION reader of input.
     */
    public IterableIonReader getInputReader(Side side) {
        return side == Side.LEFT ? leftIonReader : rightIonReader;
    }

    /**
     * Get the row count of an input.
     * @param side Side of input.
     * @return count.
     */
    public long getInputCount(Side side) {
        return side == Side.LEFT ? leftSize : rightSize;
    }

    /**
     * Get the row count of output.
     * @return count.
     */
    public long getOutputSize() {
        return outputSize;
    }

    /**
     * Get URI of output.
     * @return URI
     */
    public URI getOutputUri() {
        return outputUri;
    }

    /**
     * Get state of task.
     * @return state
     */
    public JoinState getState() {
        return state;
    }

    /**
     * Set state of task.
     * @param state
     */
    public void setState(JoinState state) {
        this.state = state;
    }

    /**
     * Log a message at a WARN level.
     * @param message
     */
    public void warn(String message) {
        logger.warn(message);
    }

    /**
     * Adds a row to the writer by merging left and right data.
     * @param leftRow Row (IonStruct) from left input.
     * @param rightRow Row (IonStruct) from right input.
     */
    public void writeMergedRow(IonStruct leftRow, IonStruct rightRow) {
        try {
            ionWriter.stepIn(IonType.STRUCT);

            if (outputMode == OutputMode.NEST) {
                ionWriter.setFieldName(leftLabel);
                IonStruct leftChildRow = ionSystem.newEmptyStruct();

                for (IonValue field : leftRow) {
                    // Field must be cloned because it cannot be a member of multiple structs.
                    leftChildRow.add(field.getFieldName(), field.clone());
                }
                leftChildRow.writeTo(ionWriter);

                ionWriter.setFieldName(rightLabel);
                IonStruct rightChildRow = ionSystem.newEmptyStruct();
                for (IonValue field : rightRow) {
                    // Field must be cloned because it cannot be a member of multiple structs.
                    rightChildRow.add(field.getFieldName(), field.clone());
                }
                rightChildRow.writeTo(ionWriter);

            } else {
                Set<String> writtenFields = new HashSet<>();

                for (IonValue field : leftRow) {
                    writtenFields = writeField(field, Side.LEFT, writtenFields);
                }

                for (IonValue field : rightRow) {
                    writtenFields = writeField(field, Side.RIGHT, writtenFields);
                }
            }

            ionWriter.stepOut();
            outputSize++;
        } catch (Exception e) {
            throw new WriteException(leftRow.toString(), rightRow.toString(), e);
        }
    }

    /**
     * Copy a row from an input to the writer (non-matched or semi join entry).
     * @param row Row (IonStruct) from the input.
     * @param side Side of the input (left or right).
     */
    public void writeSideRow(IonStruct row, Side side) {
        try {
            ionWriter.stepIn(IonType.STRUCT);

            if (outputMode == OutputMode.NEST) {
                if (side == Side.LEFT) {
                    ionWriter.setFieldName(leftLabel);
                } else {
                    ionWriter.setFieldName(rightLabel);
                }
                IonStruct childRow = ionSystem.newEmptyStruct();
                for (IonValue field : row) {
                    // Field must be cloned because it cannot be a member of multiple structs.
                    childRow.add(field.getFieldName(), field.clone());
                }
                childRow.writeTo(ionWriter);

            } else {
                Set<String> writtenFields = new HashSet<>();

                for (IonValue field : row) {
                    writtenFields = writeField(field, side, writtenFields);
                }
            }

            ionWriter.stepOut();
            outputSize++;
        } catch (Exception e) {
            throw new WriteException(row.toString(), side, e);
        }
    }

    /**
     * Write a field to the writer.
     * @param field Field as ION value.
     * @param side Side from which the field comes.
     * @param writtenFields Current set of already written fields.
     * @return Updated set of written fields.
     */
    private Set<String> writeField(IonValue field, Side side, Set<String> writtenFields) {
        String fieldName = null;
        switch (outputMode) {
            case OVERWRITE:
                fieldName = field.getFieldName();
                if (!writtenFields.contains(fieldName)) {
                    ionWriter.setFieldName(fieldName);
                    field.writeTo(ionWriter);
                    writtenFields.add(fieldName);
                }
                break;
            case PREFIX:
                fieldName = side == Side.LEFT ? leftLabel + prefixSeparator + field.getFieldName() :
                    rightLabel + prefixSeparator + field.getFieldName();
                if (!writtenFields.contains(fieldName)) {
                    ionWriter.setFieldName(fieldName);
                    field.writeTo(ionWriter);
                    writtenFields.add(fieldName);
                }
                break;
        }

        return writtenFields;
    }

    /**
     * Completes the output file and stores it in the Kestra storage.
     * @return URI of the output file.
     */
    public URI storeOutputFile() {
        try {
            closeWriter();
            outputUri = kestraContext.storage().putFile(outputFile.toFile());

            return outputUri;

        } catch (Exception e) {
            throw new StorageException(e);
        }
    }

    /**
     * Closes the inputs and the output file. Safe to call more than once.
     * @throws IOException If a stream cannot be closed.
     */
    @Override
    public void close() throws IOException {
        try {
            closeWriter();
        } finally {
            try {
                if (leftIonReader != null) leftIonReader.close();
            } finally {
                leftIonReader = null;
                try {
                    if (rightIonReader != null) rightIonReader.close();
                } finally {
                    rightIonReader = null;
                }
            }
        }
    }

    /**
     * Flushes and closes the ION writer and the output file stream, if still open.
     * @throws IOException If the output cannot be written or closed.
     */
    private void closeWriter() throws IOException {
        try {
            if (ionWriter != null) ionWriter.close();
        } finally {
            ionWriter = null;
            if (outputStream != null) {
                OutputStream stream = outputStream;
                outputStream = null;
                stream.close();
            }
        }
    }

    /**
     * Fetch input file from Kestra storage and creates a ION reader from it.
     * @param inputUri URI of input file.
     * @return ION reader for the input, to be closed by the caller.
     */
    private IterableIonReader createInputReader(URI inputUri) {
        InputStream inputStream = null;
        try {
            inputStream = kestraContext
                .storage()
                .getFile(inputUri);
            return new IterableIonReader(inputStream, ionSystem);
        } catch (Exception e) {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException closeException) {
                    e.addSuppressed(closeException);
                }
            }
            throw new ReadException(inputUri.toString(), e);
        }
    }

    /**
     * Simple parsing of an input to count rows.
     * @param side Side of the input (left or right).
     * @return Number of rows.
     */
    private long countInputRows(Side side) {
        URI inputUri = side == Side.LEFT ? leftUri : rightUri;
        long count = 0;

        try (IterableIonReader reader = createInputReader(inputUri)) {
            for (IonStruct ignored : reader) {
                count++;
            }
        } catch (IOException e) {
            throw new ReadException(inputUri.toString(), e);
        }

        if (count < 1) warn(String.format(Messages.EMPTY_INPUT, side.toString()));

        return count;
    }
}
