package io.kestra.plugin.join.util;

import java.util.Set;
import java.util.EnumSet;

import com.amazon.ion.IonType;

/**
 * Subset of ION types that can be processed.
 */
public final class IonTypes {

    private IonTypes() {}

    /** Subset of IonType enum. */
    private static final Set<IonType> PROCESSABLE = EnumSet.of(
        IonType.BOOL,
        IonType.DECIMAL,
        IonType.FLOAT,
        IonType.INT,
        IonType.NULL,
        IonType.STRING,
        IonType.STRUCT,
        IonType.TIMESTAMP
    );

    /** True if this ION type can be processed. */
    public static boolean isProcessable(IonType ionType) {
        return IonTypes.PROCESSABLE.contains(ionType);
    }
}
