package io.kestra.plugin.join.algo;

import io.kestra.plugin.join.model.JoinKey;
import io.kestra.plugin.join.model.HashTableRow;
import io.kestra.plugin.join.io.JoinIOHandler;
import io.kestra.plugin.join.util.Side;
import io.kestra.plugin.join.util.JoinType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amazon.ion.IonStruct;
import com.amazon.ion.IonValue;

/**
 * Hash Join implementation.
 */
public class HashJoin {

    /** JoinIOHandler instance. */
    private JoinIOHandler ioHandler;

    /** Join type. */
    private JoinType joinType;

    /** Option to keep only the first match in the output on semi joins. */
    private boolean onlyFirstMatchOnSemi;

    /** Hash table of the smaller input. */
    private Map<IonValue, List<HashTableRow>> hashTable;

    /** Side of the hash table. */
    private Side tableSide;

    /** Opposite side from the hash table. */
    private Side oppositeSide;

    /** Join key of input corresponding to hash table. */
    private JoinKey tableKey;

    /** Join key of input corresponding to the opposite side from hash table. */
    private JoinKey oppositeKey;

    /**
     * Creates a new HashJoin.
     * @param ioHandler JoinIOHandler instance.
     * @param leftKey Join key of left input.
     * @param rightKey Join key of right input.
     * @param joinType Join type.
     * @param onlyFirstMatchOnSemi Option to keep only the first match in the output on semi joins.
     */
    public HashJoin(
        JoinIOHandler ioHandler,
        JoinKey leftKey,
        JoinKey rightKey,
        JoinType joinType,
        boolean onlyFirstMatchOnSemi
    ) {

        this.ioHandler = ioHandler;
        this.joinType = joinType;
        this.onlyFirstMatchOnSemi = onlyFirstMatchOnSemi;

        tableSide = getTableSide();
        oppositeSide = tableSide == Side.LEFT ? Side.RIGHT : Side.LEFT;

        tableKey = tableSide == Side.LEFT ? leftKey : rightKey;
        oppositeKey = oppositeSide == Side.LEFT ? leftKey : rightKey;
    }

    /**
     * Performs the join.
     */
    public void join() {
        build();

        probe();

        if (joinType != JoinType.INNER && joinType != JoinType.LEFT_SEMI && joinType != JoinType.RIGHT_SEMI) {
            processRemainingMismatchedRows();
        }
    }

    /**
     * Get the side of the best input to build the hash table for hash join (i.e. the smaller).
     * @return The side (left or right).
     */
    private Side getTableSide() {
        long leftSize = ioHandler.getInputCount(Side.LEFT);
        long rightSize = ioHandler.getInputCount(Side.RIGHT);
        return leftSize <= rightSize ? Side.LEFT : Side.RIGHT;
    }

    /**
     * Build stage for hash join: building a hash table from an input.
     */
    private void build() {
        this.hashTable = new HashMap<>();
        IonValue keyValue = null;

        for (IonStruct row : ioHandler.getInputReader(tableSide)) {
            keyValue = tableKey.extractKeyValue(row);
            hashTable.computeIfAbsent(keyValue, k -> new ArrayList<>()).add(new HashTableRow(row));
        }
    }

    /**
     * Probe stage for hash join: parsing the opposite input of the hash table to search matches (and writing results).
     */
    private void probe() {
        IonValue keyValue = null;
        List<HashTableRow> currentMatches = null;

        // This collection is used to store matched opposite rows when 'onlyFirstMatchOnSemi' option is enabled
        Set<IonStruct> oppositeMatched = Collections.newSetFromMap(new IdentityHashMap<>());

        for (IonStruct oppositeRow : ioHandler.getInputReader(oppositeSide)) {
            keyValue = oppositeKey.extractKeyValue(oppositeRow);

            currentMatches = hashTable.get(keyValue);

            if (currentMatches != null &&
                keyValue != null &&
                !keyValue.isNullValue()) {

                // Writes all matched rows in hash table for current row of opposite side
                for (HashTableRow hashTableRow : currentMatches) {
                    switch(joinType) {
                        case JoinType.INNER:
                        case JoinType.FULL_OUTER:
                        case JoinType.LEFT_OUTER:
                        case JoinType.RIGHT_OUTER:
                            if (tableSide == Side.LEFT) {
                                ioHandler.writeMergedRow(hashTableRow.getStruct(), oppositeRow);
                            } else {
                                ioHandler.writeMergedRow(oppositeRow, hashTableRow.getStruct());
                            }
                            break;
                        case JoinType.LEFT_SEMI:
                            /*
                            If onlyFirstMatchOnSemi is enabled, and if semi join side is opposite to hash table side,
                            matched row from opposite side must be stored and searched to prevent duplicates
                            */
                            if (onlyFirstMatchOnSemi &&
                                ((tableSide == Side.LEFT && hashTableRow.isMatched()) ||
                                (tableSide == Side.RIGHT && oppositeMatched.contains(oppositeRow)))) {
                                    break;
                            }
                            if (tableSide == Side.LEFT) {
                                ioHandler.writeSideRow(hashTableRow.getStruct(), Side.LEFT);
                            } else {
                                ioHandler.writeSideRow(oppositeRow, Side.LEFT);
                                if (onlyFirstMatchOnSemi) oppositeMatched.add(oppositeRow);
                            }
                            break;
                        case JoinType.RIGHT_SEMI:
                            if (onlyFirstMatchOnSemi &&
                                ((tableSide == Side.RIGHT && hashTableRow.isMatched()) ||
                                (tableSide == Side.LEFT && oppositeMatched.contains(oppositeRow)))) {
                                    break;
                            }
                            if (tableSide == Side.RIGHT) {
                                ioHandler.writeSideRow(hashTableRow.getStruct(), Side.RIGHT);
                            } else {
                                ioHandler.writeSideRow(oppositeRow, Side.RIGHT);
                                if (onlyFirstMatchOnSemi) oppositeMatched.add(oppositeRow);
                            }
                            break;
                    }
                    hashTableRow.setMatched();
                }

            // Writes mismatched row of opposite side for outer and anti joins
            } else if (joinType != JoinType.INNER && joinType != JoinType.LEFT_SEMI && joinType != JoinType.RIGHT_SEMI) {
                switch (joinType) {
                    case FULL_OUTER:
                        if (tableSide == Side.RIGHT) {
                            ioHandler.writeSideRow(oppositeRow, Side.LEFT);
                        } else {
                            ioHandler.writeSideRow(oppositeRow, Side.RIGHT);
                        }
                        break;
                    case LEFT_OUTER:
                    case LEFT_ANTI:
                        if (tableSide == Side.RIGHT) {
                            ioHandler.writeSideRow(oppositeRow, Side.LEFT);
                        }
                        break;
                    case RIGHT_OUTER:
                    case RIGHT_ANTI:
                        if (tableSide == Side.LEFT) {
                            ioHandler.writeSideRow(oppositeRow, Side.RIGHT);
                        }
                        break;
                }
            }
        }
    }

    /**
     * For outer and anti joins, final step of hash join: parsing remaining mismatched rows in the hash table and writing results.
     */
    private void processRemainingMismatchedRows() {
        for (List<HashTableRow> hashTableValues : hashTable.values()) {
            for (HashTableRow hashTableRow : hashTableValues) {
                if (!hashTableRow.isMatched()) {
                    switch (joinType) {
                        case FULL_OUTER:
                            if (tableSide == Side.LEFT) {
                                ioHandler.writeSideRow(hashTableRow.getStruct(), Side.LEFT);
                            } else {
                                ioHandler.writeSideRow(hashTableRow.getStruct(), Side.RIGHT);
                            }
                            break;
                        case LEFT_OUTER:
                        case LEFT_ANTI:
                            if (tableSide == Side.LEFT) {
                                ioHandler.writeSideRow(hashTableRow.getStruct(), Side.LEFT);
                            }
                            break;
                        case RIGHT_OUTER:
                        case RIGHT_ANTI:
                            if (tableSide == Side.RIGHT) {
                                ioHandler.writeSideRow(hashTableRow.getStruct(), Side.RIGHT);
                            }
                            break;
                    }
                }
            }
        }
    }
}
