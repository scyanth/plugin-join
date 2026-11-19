package io.kestra.plugin.join.algo;

import io.kestra.plugin.join.model.JoinKey;
import io.kestra.plugin.join.io.JoinIOHandler;
import io.kestra.plugin.join.util.Side;
import io.kestra.plugin.join.util.JoinType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.amazon.ion.IonStruct;
import com.amazon.ion.IonValue;

/**
 * Nested Loop Join implementation.
 */
public class NestedLoopJoin {

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

    /**
     * Creates a new NestedLoopJoin.
     * @param ioHandler JoinIOHandler instance.
     * @param leftKey Join key of left input.
     * @param rightKey Join key of right input.
     * @param joinType Join type.
     * @param onlyFirstMatchOnSemi Option to keep only the first match in the output on semi joins.
     */
    public NestedLoopJoin(
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
        IonValue leftKeyValue = null;
        IonValue rightKeyValue = null;
        Set<IonStruct> leftMatched = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IonStruct> rightMatched = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean matched;

        List<IonStruct> rightRows = new ArrayList<>();
        for (IonStruct rightRow : ioHandler.getInputReader(Side.RIGHT)) {
            rightRows.add(rightRow);
        }

        long leftSize = ioHandler.getInputCount(Side.LEFT);

        for (IonStruct leftRow : ioHandler.getInputReader(Side.LEFT)) {

            matched = false;
            leftKeyValue = leftKey.extractKeyValue(leftRow);

            for (IonStruct rightRow : rightRows) {

                rightKeyValue = rightKey.extractKeyValue(rightRow);

                if (leftKeyValue != null &&
                    !leftKeyValue.isNullValue() &&
                    rightKeyValue != null &&
                    !rightKeyValue.isNullValue() &&
                    leftKeyValue.equals(rightKeyValue)) {

                    matched = true;

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

            if (!matched &&
                (joinType == JoinType.FULL_OUTER || joinType == JoinType.LEFT_OUTER || joinType == JoinType.LEFT_ANTI)) {
                ioHandler.writeSideRow(leftRow, Side.LEFT);
            }
        }
        if (joinType == JoinType.FULL_OUTER || joinType == JoinType.RIGHT_OUTER || joinType == JoinType.RIGHT_ANTI) {
            for (IonStruct rightRow : rightRows) {
                if (!rightMatched.contains(rightRow)) {
                    ioHandler.writeSideRow(rightRow, Side.RIGHT);
                }
            }
        }
        if (leftSize < 1 &&
            (joinType == JoinType.FULL_OUTER || joinType == JoinType.RIGHT_OUTER || joinType == JoinType.RIGHT_ANTI)) {
            for (IonStruct rightRow : rightRows) {
                ioHandler.writeSideRow(rightRow, Side.RIGHT);
            }
        }
    }
}
