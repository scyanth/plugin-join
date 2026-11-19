package io.kestra.plugin.join.model;

import com.amazon.ion.IonStruct;

/**
 * Abstraction of a row from hash table (for hash join), combining row data with a match metadata to keep trace of join matches.
 */
public final class HashTableRow {

    /** ION struct of row data. */
    private IonStruct struct;

    /** True if this row is matched with at least one row from other side. */
    private boolean matched = false;

    /**
     * Creates a new HashTableRow.
     * @param struct ION struct of row data.
     */
    public HashTableRow(IonStruct struct) {
        this.struct = struct;
        this.matched = false;
    }

    /**
     * Get row data.
     * @return ION struct of row data.
     */
    public IonStruct getStruct() {
        return this.struct;
    }

    /**
     * Get match metadata.
     * @return True if this row is matched with at least one row from other side.
     */
    public boolean isMatched() {
        return this.matched;
    }

    /**
     * Sets match metadata as true.
     */
    public void setMatched() {
        this.matched = true;
    }
}