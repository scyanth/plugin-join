package io.kestra.plugin.join;

import io.kestra.plugin.join.io.JoinIOHandler;
import io.kestra.plugin.join.model.JoinKey;
import io.kestra.plugin.join.algo.HashJoin;
import io.kestra.plugin.join.algo.NestedLoopJoin;
import io.kestra.plugin.join.algo.SortedMergeJoin;
import io.kestra.plugin.join.util.JoinImplementation;
import io.kestra.plugin.join.util.JoinType;
import io.kestra.plugin.join.util.OutputMode;
import io.kestra.plugin.join.util.Side;
import io.kestra.plugin.join.util.MissingKeyAction;
import io.kestra.plugin.join.util.JoinState;
import io.kestra.plugin.join.exception.RenderException;
import io.kestra.plugin.join.exception.BlankPropertyException;
import io.kestra.plugin.join.util.Messages;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.flows.State;
import io.kestra.core.runners.RunContext;

import java.net.URI;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Join ION files",
    description = "Apply various join operations on two ION files."
)
@Plugin(
    examples = {
        @Example(
            title = "Inner join of customers and their orders",
            full = true,
            code = """
                id: join_customers_orders
                namespace: company.team

                tasks:
                  - id: customers
                    type: io.kestra.plugin.core.storage.Write
                    extension: .ion
                    content: |
                      {customer_id:1,name:"Alice"}
                      {customer_id:2,name:"Bob"}
                      {customer_id:3,name:"Carol"}

                  - id: orders
                    type: io.kestra.plugin.core.storage.Write
                    extension: .ion
                    content: |
                      {order_id:101,customer_id:1,total:25.50}
                      {order_id:102,customer_id:1,total:12.00}
                      {order_id:103,customer_id:3,total:40.00}
                      {order_id:104,customer_id:4,total:8.75}

                  - id: join
                    type: io.kestra.plugin.join.Join
                    left: "{{ outputs.customers.uri }}"
                    right: "{{ outputs.orders.uri }}"
                    leftKey: customer_id
                    rightKey: customer_id
                    joinType: INNER
                """
        ),
        @Example(
            title = "Left outer join on a nested key, keeping each side in its own object",
            full = true,
            code = """
                id: join_customers_orders_nested
                namespace: company.team

                tasks:
                  - id: customers
                    type: io.kestra.plugin.core.storage.Write
                    extension: .ion
                    content: |
                      {customer:{id:1},name:"Alice"}
                      {customer:{id:2},name:"Bob"}

                  - id: orders
                    type: io.kestra.plugin.core.storage.Write
                    extension: .ion
                    content: |
                      {order_id:101,customer_id:1,total:25.50}

                  - id: join
                    type: io.kestra.plugin.join.Join
                    left: "{{ outputs.customers.uri }}"
                    right: "{{ outputs.orders.uri }}"
                    leftKey: customer.id
                    rightKey: customer_id
                    joinType: LEFT_OUTER
                    outputMode: NEST
                    leftLabel: customer
                    rightLabel: order
                """
        )
    }
)
public class Join extends Task implements RunnableTask<Join.Output> {
    /**
     * INPUTS ------------------------------------------------------------------------------------------------------------------- //
    **/

    @Schema(
        title = "Left data",
        description = "Must be a `kestra://` internal storage or resolvable pebble URI."
    )
    @PluginProperty(group = "source", internalStorageURI = true)
    @NotNull
    private Property<URI> left;

    @Schema(
        title = "Right data",
        description = "Must be a `kestra://` internal storage or resolvable pebble URI."
    )
    @PluginProperty(group = "source", internalStorageURI = true)
    @NotNull
    private Property<URI> right;

    @Schema(
        title = "Key field name or nested key of left input, that will be compared to the right input (equality)",
        description = "Use the `.` sign to make a nested key (e.g. `object.id`)."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> leftKey;

    @Schema(
        title = "Key field name or nested key of right input, that will be compared to the left input (equality)",
        description = "Use the `.` sign to make a nested key (e.g. `object.id`)."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> rightKey;

    @Schema(
        title = "Join implementation (algorithm)",
        description = """
            All implementations return the same rows, possibly in a different order.
            - `HASH` (default): builds a hash table from the smaller input, then reads the other input to find matches.
            - `NESTED_LOOP`: compares every row of one input with every row of the other; only suited to small inputs.
            - `SORTED_MERGE`: sorts both inputs on the key, then merges them; the key must have the same type in every row of both inputs."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<JoinImplementation> joinImplementation = Property.ofValue(JoinImplementation.HASH);

    @Schema(
        title = "Join type",
        description = """
            - `INNER` (default): only rows whose key matches on both sides.
            - `FULL_OUTER`, `LEFT_OUTER`, `RIGHT_OUTER`: matched rows, plus unmatched rows from both sides, the left side or the right side.
            - `LEFT_ANTI`, `RIGHT_ANTI`: only the rows of one side that have no match on the other side.
            - `LEFT_SEMI`, `RIGHT_SEMI`: only the rows of one side that have a match, without fields from the other side (see `onlyFirstMatchOnSemi`)."""
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<JoinType> joinType = Property.ofValue(JoinType.INNER);

    @Schema(
        title = "Output mode",
        description = "Configure output structure to differentiate fields by side."
    )
    @PluginProperty(group = "destination")
    @Builder.Default
    private Property<OutputMode> outputMode = Property.ofValue(OutputMode.OVERWRITE);

    @Schema(
        title = "Label for left fields in output",
        description = "Depending on output mode, used as prefix in combination with prefixSeparator, or object key for left fields. Must differ from rightLabel."
    )
    @PluginProperty(group = "destination")
    @NotBlank
    @Builder.Default
    private String leftLabel = "left";

    @Schema(
        title = "Label for right fields in output",
        description = "Depending on output mode, used as prefix in combination with prefixSeparator, or object key for right fields. Must differ from leftLabel."
    )
    @PluginProperty(group = "destination")
    @NotBlank
    @Builder.Default
    private String rightLabel = "right";

    @Schema(
        title = "Separator character for prefixes",
        description = "When output mode is prefix, will be concatenated between label and field names. Can be blank."
    )
    @PluginProperty(group = "destination")
    @Builder.Default
    private String prefixSeparator = "_";

    @Schema(
        title = "Keep only the first match in the output on semi joins",
        description = "If false, multiple identical rows can appear for each matching occurrence."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Boolean> onlyFirstMatchOnSemi = Property.ofValue(false);

    @Schema(
        title = "What to do when key field is missing from an input (for at least one row)",
        description = """
            Applies when a row has no key field, or a `null` key value.
            - `FAIL` (default): the task fails with an error showing the row.
            - `WARNING`: the task continues and ends in `WARNING` state.
            - `IGNORE`: the task continues and its state is not changed.

            With `WARNING` and `IGNORE`, the first such row of each input is logged, and rows without a key never match."""
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<MissingKeyAction> onMissingKey = Property.ofValue(MissingKeyAction.FAIL);

    /**
     * VALIDATIONS --------------------------------------------------------------------------------------------------------------- //
    **/

    /**
     * Checks that left and right labels are distinct.
     * @return True if labels are distinct or one of them is missing (handled by other constraints).
     */
    @Schema(hidden = true)
    @JsonIgnore
    @AssertTrue(message = "leftLabel and rightLabel must be different")
    public boolean isLabelsDistinct() {
        return leftLabel == null || rightLabel == null || !leftLabel.equals(rightLabel);
    }

    /**
     * OUTPUTS ------------------------------------------------------------------------------------------------------------------- //
    **/

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(hidden = true)
        @JsonIgnore
        private JoinState state;

        @Schema(
            title = "Joined tables",
            description = "A file that contains matched entries, may be empty."
        )
        private final URI uri;

        @Schema(
            title = "The number of rows of output",
            description = "Number of rows written to the output file."
        )
        private final long outputSize;

        @Schema(
            title = "The number of rows of left input",
            description = "Number of rows read from the left input file."
        )
        private final long leftSize;

        @Schema(
            title = "The number of rows of right input",
            description = "Number of rows read from the right input file."
        )
        private final long rightSize;

        @Override
        public Optional<State.Type> finalState() {
            if (state == null) return Optional.empty();
            switch (state) {
                case JoinState.WARNING:
                    return Optional.of(State.Type.WARNING);
                default:
                    return Optional.empty();
            }
        }
    }

    /**
     * CODE ---------------------------------------------------------------------------------------------------------------------- //
    **/

    /** {@inheritDoc} */
    @Override
    public Join.Output run(RunContext runContext) throws Exception {
        URI renderedLeft =                              requireNonBlank(render(left, URI.class, "left", runContext), "left");
        URI renderedRight =                             requireNonBlank(render(right, URI.class, "right", runContext), "right");
        String renderedLeftKey =                        requireNonBlank(render(leftKey, String.class, "leftKey", runContext), "leftKey");
        String renderedRightKey =                       requireNonBlank(render(rightKey, String.class, "rightKey", runContext), "rightKey");
        JoinImplementation renderedJoinImplementation = render(joinImplementation, JoinImplementation.class, "joinImplementation", runContext);
        JoinType renderedJoinType =                     render(joinType, JoinType.class, "joinType", runContext);
        OutputMode renderedOutputMode =                 render(outputMode, OutputMode.class, "outputMode", runContext);
        boolean renderedOnlyFirstMatchOnSemi =          render(onlyFirstMatchOnSemi, Boolean.class, "onlyFirstMatchOnSemi", runContext);
        MissingKeyAction renderedOnMissingKey =         render(onMissingKey, MissingKeyAction.class, "onMissingKey", runContext);

        JoinIOHandler ioHandler = new JoinIOHandler(
            runContext,
            renderedLeft,
            renderedRight,
            leftLabel,
            rightLabel,
            prefixSeparator,
            renderedOutputMode
        );

        long leftSize = ioHandler.getInputCount(Side.LEFT);
        long rightSize = ioHandler.getInputCount(Side.RIGHT);

        JoinKey leftJoinKey = new JoinKey(renderedLeftKey, Side.LEFT, renderedOnMissingKey, ioHandler);
        JoinKey rightJoinKey = new JoinKey(renderedRightKey, Side.RIGHT, renderedOnMissingKey, ioHandler);

        switch (renderedJoinImplementation) {
            case HASH:
                HashJoin hashJoin = new HashJoin(
                    ioHandler,
                    leftJoinKey,
                    rightJoinKey,
                    renderedJoinType,
                    renderedOnlyFirstMatchOnSemi
                );

                hashJoin.join();

                break;
            case NESTED_LOOP:
                NestedLoopJoin nestedLoop = new NestedLoopJoin(
                    ioHandler,
                    leftJoinKey,
                    rightJoinKey,
                    renderedJoinType,
                    renderedOnlyFirstMatchOnSemi
                );

                nestedLoop.join();

                break;
            case SORTED_MERGE:
                SortedMergeJoin sortedMerge = new SortedMergeJoin(
                    ioHandler,
                    leftJoinKey,
                    rightJoinKey,
                    renderedJoinType,
                    renderedOnlyFirstMatchOnSemi
                );

                sortedMerge.join();

                break;
        }

        URI result = ioHandler.storeOutputFile();
        long outputSize = ioHandler.getOutputSize();
        JoinState state = ioHandler.getState();

        return Output.builder()
            .uri(result)
            .outputSize(outputSize)
            .leftSize(leftSize)
            .rightSize(rightSize)
            .state(state)
            .build();
    }

    /**
     * Renders a dynamic property with Pebble expression or raw value.
     * Exceptions are thrown with property name.
     * @param <T> Expected type of the rendered value.
     * @param property The property to render.
     * @param type Expected class of the rendered value.
     * @param propertyName Name of the property.
     * @param runContext Kestra run Context.
     * @return The rendered value.
     */
    private <T> T render(
        Property<T> property,
        Class<T> type,
        String propertyName,
        RunContext runContext) {
        try {
            return runContext.render(property)
                .as(type)
                .orElseThrow();
        } catch (Exception e) {
            throw new RenderException(propertyName, e);
        }
    }

    /**
     * Checks that a rendered string property is not blank.
     * @param value The rendered value.
     * @param propertyName Name of the property.
     * @return The value if not blank.
     */
    private URI requireNonBlank(URI value, String propertyName) {
        if (value == null || value.toString().isBlank()) {
            throw new BlankPropertyException(propertyName);
        }
        return value;
    }

    /**
     * Checks that a rendered string property is not blank.
     * @param value The rendered value.
     * @param propertyName Name of the property.
     * @return The value if not blank.
     */
    private String requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new BlankPropertyException(propertyName);
        }
        return value;
    }
}