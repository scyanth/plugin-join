package io.kestra.plugin.join.io;

import com.amazon.ion.IonReader;
import com.amazon.ion.IonStruct;
import com.amazon.ion.IonSystem;
import com.amazon.ion.IonType;

import java.io.InputStream;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Wrapper of IonReader that makes it iterable.
 */
public class IterableIonReader implements Iterable<IonStruct> {

    /** ION reader instance. */
    private IonReader reader;

    /** ION system to handle the format. */
    private IonSystem ionSystem;

    /**
     * Creates a new IterableIonReader.
     * @param inputStream Data stream of input.
     * @param ionSystem ION system to handle the format.
     */
    public IterableIonReader(InputStream inputStream, IonSystem ionSystem) {
        this.ionSystem = ionSystem;
        this.reader = ionSystem.newReader(inputStream);
    }

    @Override
    public Iterator<IonStruct> iterator() {
        return new Iterator<>() {
            IonType nextType = reader.next();

            @Override
            public boolean hasNext() {
                return nextType != null;
            }

            @Override
            public IonStruct next() {
                if (nextType == null) {
                    throw new NoSuchElementException();
                }
                IonStruct struct = (IonStruct) ionSystem.newValue(reader);
                nextType = reader.next();
                return struct;
            }
        };
    }
}
