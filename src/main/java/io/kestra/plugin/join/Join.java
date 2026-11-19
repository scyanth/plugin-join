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
        @io.kestra.core.models.annotations.Example(
            title = "Inner join on SQL queries outputs",
            full = true,
            code = """
                id: sql_join
                namespace: company.team

                tasks:
                  - id: join
                    type: io.kestra.plugin.join.Join
                    left: "{{ outputs.selectPeople.uri }}"
                    right: "{{ outputs.selectCompany.uri }}"
                    joinType: INNER
                    leftKey: company_id
                    rightKey: id
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
    @PluginProperty(group = "main")
    @NotNull
    private Property<URI> left;

    @Schema(
        title = "Right data",
        description = "Must be a `kestra://` internal storage or resolvable pebble URI."
    )
    @PluginProperty(group = "main")
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
        description = ""
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<JoinImplementation> joinImplementation = Property.ofValue(JoinImplementation.HASH);

    @Schema(
        title = "Join type",
        description = ""
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<JoinType> joinType = Property.ofValue(JoinType.INNER);

    @Schema(
        title = "Output mode",
        description = "Configure output structure to differenciate fields by side."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<OutputMode> outputMode = Property.ofValue(OutputMode.OVERWRITE);

    @Schema(
        title = "Label for left fields in output",
        description = "Depending of output mode, used as prefix in combination with prefixSeparator, or object key for left fields. Must differ from rightLabel."
    )
    @PluginProperty(group = "main")
    @NotBlank
    @Builder.Default
    private String leftLabel = "left";

    @Schema(
        title = "Label for right fields in output",
        description = "Depending of output mode, used as prefix in combination with prefixSeparator, or object key for right fields. Must differ from leftLabel."
    )
    @PluginProperty(group = "main")
    @NotBlank
    @Builder.Default
    private String rightLabel = "right";

    @Schema(
        title = "Separator character for prefixes",
        description = "When output mode is prefix, will be concatenated between label and field names. Can be blank."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private String prefixSeparator = "_";

    @Schema(
        title = "Keep only the first match in the output on semi joins",
        description = "If false, multiple identical rows can appear for each matching occurrence."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Boolean> onlyFirstMatchOnSemi = Property.ofValue(false);

    @Schema(
        title = "What to do when key field is missing from an input (for at least one row)",
        description = ""
    )
    @PluginProperty(group = "main")
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
            description = ""
        )
        private final long outputSize;

        @Schema(
            title = "The number of rows of left input",
            description = ""
        )
        private final long leftSize;

        @Schema(
            title = "The number of rows of right input",
            description = ""
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