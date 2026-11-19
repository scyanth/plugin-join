package io.kestra.plugin.join.algo;

import io.kestra.plugin.join.model.JoinKey;
import io.kestra.plugin.join.io.JoinIOHandler;
import io.kestra.plugin.join.util.Side;
import io.kestra.plugin.join.util.JoinType;
import io.kestra.plugin.join.exception.DifferentTypesException;
import io.kestra.plugin.join.exception.UnprocessableTypeException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Comparator;

import com.amazon.ion.IonStruct;
import com.amazon.ion.IonValue;
import com.amazon.ion.IonType;
import com.amazon.ion.IonString;
import com.amazon.ion.IonTimestamp;
import com.amazon.ion.IonBool;
import com.amazon.ion.IonInt;
import com.amazon.ion.IonFloat;
import com.amazon.ion.IonDecimal;

/**
 * Sorted Merge Join implementation.
 */
public class SortedMergeJoin {

    /** JoinIOHandler instance. */
    private JoinIOHandler ioHandler;

    /** Join type. */
    private JoinType joinType;

    /** Option to keep only the first match in the output on semi joins. */
    private boolean onlyFirstMatchOnSemi;

    /** Join key of left input. */
    private JoinKey leftKey;

    /** Join key of right input. */
    private JoinKey rightKey;

    /** Sorted row list from left input. */
    private List<IonStruct> sortedLeft;

    /** Sorted row list from right input. */
    private List<IonStruct> sortedRight;

    /**
     * Creates a new SortedMergeJoin.
     * @param ioHandler JoinIOHandler instance.
     * @param leftKey Join key of left input.
     * @param rightKey Join key of right input.
     * @param joinType Join type.
     * @param onlyFirstMatchOnSemi Option to keep only the first match in the output on semi joins.
     */
    public SortedMergeJoin(
        JoinIOHandler ioHandler,
        JoinKey leftKey,
        JoinKey rightKey,
        JoinType joinType,
        boolean onlyFirstMatchOnSemi
    ) {
        this.ioHandler = ioHandler;
        this.joinType = joinType;
        this.onlyFirstMatchOnSemi = onlyFirstMatchOnSemi;
        this.leftKey = leftKey;
        this.rightKey = rightKey;
    }

    /**
     * Performs the join.
     */
    public void join() {
        sortedLeft = sort(leftKey, Side.LEFT);
        sortedRight = sort(rightKey, Side.RIGHT);

        mergeLoop();
    }

    /**
     * Sort stage for sorted merge join (on one side).
     * @param key Key field for the input.
     * @param side The side of the input (left or right).
     * @return A sorted list from the input.
     */
    private List<IonStruct> sort(JoinKey key, Side side) {
        List<IonStruct> list = new ArrayList<>();

        for (IonStruct row : ioHandler.getInputReader(side)) {
            list.add(row);
        }

        list.sort(comparatorByKey(key));

        return list;
    }

    /**
     * Merge stage for sorted merge join: loop over sorted inputs to search matches (and writing results).
     */
    private void mergeLoop() {
        int l = 0;
        int r = 0;
        long leftSize = sortedLeft.size();
        long rightSize = sortedRight.size();
        Set<IonStruct> leftMatched = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IonStruct> rightMatched = Collections.newSetFromMap(new IdentityHashMap<>());

        while (l < leftSize && r < rightSize) {
            IonStruct leftRow = sortedLeft.get(l);
            IonStruct rightRow = sortedRight.get(r);

            IonValue leftValue = leftKey.extractKeyValue(leftRow);
            IonValue rightValue = rightKey.extractKeyValue(rightRow);

            int leftToRightCompare = compareIonValues(leftValue, rightValue);

            // Equality means a match (except for null values)
            if (leftToRightCompare == 0) {
                int l0 = l;
                int r0 = r;

                while (l < leftSize && compareIonValues(leftKey.extractKeyValue(sortedLeft.get(l0)),
                    leftKey.extractKeyValue(sortedLeft.get(l))) == 0) l++;

                while (r < rightSize && compareIonValues(rightKey.extractKeyValue(sortedRight.get(r0)),
                    rightKey.extractKeyValue(sortedRight.get(r))) == 0) r++;

                // Forced mismatch for null values on both sides
                if ((leftValue == null || leftValue.isNullValue())
                    && (rightValue == null || rightValue.isNullValue())) {
                    // Emit unmatched left rows when required
                    if (joinType == JoinType.FULL_OUTER
                            || joinType == JoinType.LEFT_OUTER
                            || joinType == JoinType.LEFT_ANTI) {
                        for (int i = l0; i < l; i++) {
                            ioHandler.writeSideRow(sortedLeft.get(i), Side.LEFT);
                        }
                    }

                    // Emit unmatched right rows when required
                    if (joinType == JoinType.FULL_OUTER
                            || joinType == JoinType.RIGHT_OUTER
                            || joinType == JoinType.RIGHT_ANTI) {
                        for (int j = r0; j < r; j++) {
                            ioHandler.writeSideRow(sortedRight.get(j), Side.RIGHT);
                        }
                    }

                    continue;
                }

                // True match
                for (int i = l0; i < l; i++) {

                    leftRow = sortedLeft.get(i);

                    for (int j = r0; j < r; j++) {

                        rightRow = sortedRight.get(j);

                        switch(joinType) {
                            case JoinType.INNER:
                            case JoinType.FULL_OUTER:
                            case JoinType.LEFT_OUTER:
                            case JoinType.RIGHT_OUTER:
                                ioHandler.writeMergedRow(leftRow, rightRow);
                                break;
                            case JoinType.LEFT_SEMI:
                                if (!onlyFirstMatchOnSemi || (onlyFirstMatchOnSemi && !leftMatched.contains(leftRow))) {
                                    ioHandler.writeSideRow(leftRow, Side.LEFT);
                                }
                                break;
                            case JoinType.RIGHT_SEMI:
                                if (!onlyFirstMatchOnSemi || (onlyFirstMatchOnSemi && !rightMatched.contains(rightRow))) {
                                    ioHandler.writeSideRow(rightRow, Side.RIGHT);
                                }
                                break;
                        }
                        leftMatched.add(leftRow);
                        rightMatched.add(rightRow);
                    }
                }
            // Mismatch from Left
            } else if (leftToRightCompare < 0) {
                if (joinType == JoinType.FULL_OUTER || joinType == JoinType.LEFT_OUTER || joinType == JoinType.LEFT_ANTI) {
                    ioHandler.writeSideRow(leftRow, Side.LEFT);
                }
                l++;
            // Mismatch from Right
            } else if (leftToRightCompare > 0) {
                if (joinType == JoinType.FULL_OUTER || joinType == JoinType.RIGHT_OUTER || joinType == JoinType.RIGHT_ANTI) {
                    ioHandler.writeSideRow(rightRow, Side.RIGHT);
                }
                r++;
            }
        }

        // Remaining non-matching rows from Left
        if (joinType == JoinType.FULL_OUTER || joinType == JoinType.LEFT_OUTER || joinType == JoinType.LEFT_ANTI) {
            while (l < leftSize) {
                IonStruct leftRow = sortedLeft.get(l);
                ioHandler.writeSideRow(leftRow, Side.LEFT);
                l++;
            }
        }

        // Remaining non-matching rows from Right
        if (joinType == JoinType.FULL_OUTER || joinType == JoinType.RIGHT_OUTER || joinType == JoinType.RIGHT_ANTI) {
            while (r < rightSize) {
                IonStruct rightRow = sortedRight.get(r);
                ioHandler.writeSideRow(rightRow, Side.RIGHT);
                r++;
            }
        }
    }

    /**
     * Applies compareIonValues() to both sides on key field and return a Comparator functional object.
     * @see compareIonValues()
     * @param key Key field for the input.
     * @return The Comparator object that encapsulates the function.
     */
    private Comparator<IonStruct> comparatorByKey(JoinKey key) {
        return (a, b) -> {
            IonValue va = key.extractKeyValue(a);
            IonValue vb = key.extractKeyValue(b);
            return compareIonValues(va, vb);
        };
    }

    /**
     * Compares two Ion values based on their type and return an integer representing their order relationship (Comparator pattern).
     * @param va The Ion value to compares to.
     * @param vb The Ion value to be compared.
     * @return The value -1 if the first value is lesser than the second, 1 if the first is greater than the second, and 0 if both are equals.
     */
    private int compareIonValues(IonValue va, IonValue vb) {
        if ((va == null || va.isNullValue()) && (vb == null || vb.isNullValue())) return 0;
        if (va == null || va.isNullValue()) return 1;
        if (vb == null || vb.isNullValue()) return -1;

        if (va.getType() != vb.getType()) throw new DifferentTypesException(va.toString(), vb.toString());

        switch(va.getType()) {

            case IonType.BOOL:
                return Boolean.compare(
                    ((IonBool) va).booleanValue(),
                    ((IonBool) vb).booleanValue()
                );

            case IonType.INT:
                return Integer.compare(
                    ((IonInt) va).intValue(),
                    ((IonInt) vb).intValue()
                );

            case IonType.FLOAT:
                return Float.compare(
                    ((IonFloat) va).floatValue(),
                    ((IonFloat) vb).floatValue()
                );

            case IonType.DECIMAL:
                return ((IonDecimal) va).decimalValue()
                    .compareTo(((IonDecimal) vb).decimalValue());

            case IonType.STRING:
                return ((IonString) va).stringValue()
                    .compareTo(((IonString) vb).stringValue());

            case IonType.TIMESTAMP:
                return ((IonTimestamp) va).timestampValue()
                    .compareTo(((IonTimestamp) vb).timestampValue());

            default:
                throw new UnprocessableTypeException();
        }
    }
}
