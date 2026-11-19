package io.kestra.plugin.join.model;

import io.kestra.plugin.join.io.JoinIOHandler;
import io.kestra.plugin.join.util.Side;
import io.kestra.plugin.join.util.IonTypes;
import io.kestra.plugin.join.util.Messages;
import io.kestra.plugin.join.util.MissingKeyAction;
import io.kestra.plugin.join.util.JoinState;
import io.kestra.plugin.join.exception.KeyValueIsObjectException;
import io.kestra.plugin.join.exception.UnprocessableTypeException;
import io.kestra.plugin.join.exception.UnreachableKeyDepthException;
import io.kestra.plugin.join.exception.MissingKeyException;

import com.amazon.ion.IonStruct;
import com.amazon.ion.IonType;
import com.amazon.ion.IonValue;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Abstraction of join key providing a data structure for nested fields, side and method for extraction.
 */
public final class JoinKey {

    /** String of the key used as input, which can be the field name or a nested key path. */
    private String keyString;

    /** Side of the key. */
    private Side keySide;

    /**
     * Structure of the key.
     * Each item is a field name forming the path of nested key.
     */
    private ArrayList<String> keyStruct;

    /** Action when key is missing. */
    private MissingKeyAction oMissingKey;

    /** If key is missing in at least one row. */
    private boolean isMissing = false;

    /** JoinIOHandler instance. */
    private JoinIOHandler ioHandler;

    /**
     * Creates a new JoinKey.
     * @param keyString String of the key used as input, which can be the field name or a nested key path.
     * @param keySide Side of the key.
     */
    public JoinKey(String keyString,
        Side keySide,
        MissingKeyAction onMissingKey,
        JoinIOHandler ioHandler) {

        this.keyString = keyString;
        this.keySide = keySide;
        this.oMissingKey = onMissingKey;
        this.ioHandler = ioHandler;
        keyStruct = new ArrayList<>();

        // Split of key string with . character used as separator for nested key path.
        if (keyString.contains(".")) {
            keyStruct = new ArrayList<>(Arrays.asList(keyString.split("\\.")));
        } else {
            keyStruct.add(keyString);
        }

    }

    /**
     * Get the key string.
     * @return String of the key used as input, which can be the field name or a nested key path.
     */
    public String getKeyString() {
        return keyString;
    }

    /**
     * Extracts value from a row by unique or nested key.
     * @param row The row where to extracts the value.
     * @return The corresponding Ion value.
     */
    public IonValue extractKeyValue(IonStruct row) {
        return traverseRow(row, keyStruct, 0);
    }

    /**
     * Recursive function to get the key value in a simple or nested row.
     * @param row The row where to extracts the value.
     * @param keyPath Array for the key path.
     * @param depth Current depth of the traversing.
     * @return The final Ion value.
     */
    private IonValue traverseRow(IonStruct row, ArrayList<String> keyPath, int depth) {
        IonValue val = row.get(keyPath.get(depth).trim());
        if (depth < keyPath.size()-1) {
            if (val != null && val.getType() == IonType.STRUCT) {
                return traverseRow((IonStruct) val, keyPath, depth+1);
            } else {
                throw new UnreachableKeyDepthException(keyString, keySide.toString());
            }
        } else {
            if (val == null || val.isNullValue()) {
                switch (oMissingKey) {
                    case MissingKeyAction.FAIL:
                        throw new MissingKeyException(keyString, keySide.toString(), row.toString());
                    case MissingKeyAction.WARNING:
                        ioHandler.setState(JoinState.WARNING);
                        if (!isMissing) {
                            ioHandler.warn(String.format(Messages.MISSING_KEY, keyString, keySide.toString(), row.toString()));
                            isMissing = true;
                        }
                        break;
                    case MissingKeyAction.IGNORE:
                        if (!isMissing) {
                            ioHandler.warn(String.format(Messages.MISSING_KEY, keyString, keySide.toString(), row.toString()));
                            isMissing = true;
                        }
                        break;
                }
            } else {
                if (val.getType() == IonType.STRUCT) {
                    throw new KeyValueIsObjectException(keyString, keySide.toString());
                }
                if (!IonTypes.isProcessable(val.getType())) {
                    throw new UnprocessableTypeException(keyString, keySide.toString());
                }
            }
            return val;
        }
    }
}
