package io.kestra.plugin.join;

import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;

import io.kestra.plugin.join.util.JoinImplementation;
import io.kestra.plugin.join.util.JoinType;
import io.kestra.plugin.join.util.MissingKeyAction;
import io.kestra.plugin.join.util.Messages;
import io.kestra.plugin.join.util.OutputMode;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.models.property.Property;

import jakarta.inject.Inject;

import com.amazon.ion.IonSystem;
import com.amazon.ion.system.IonSystemBuilder;

import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

@KestraTest
class JoinTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storageInterface;

    private String defaultKey = "id";

    private String defaultLeftContent = """
        {id:1,leftValue:"L1"}
        {id:2,leftValue:"L2-A"}
        {id:2,leftValue:"L2-B"}
        {id:3,leftValue:"L3"}
        {id:5,leftValue:"L5"}""";

    private String defaultRightContent = """
        {id:2,rightValue:"R2-A"}
        {id:2,rightValue:"R2-B"}
        {id:3,rightValue:"R3"}
        {id:4,rightValue:"R4"}
        {id:5,rightValue:"R5-A"}
        {id:5,rightValue:"R5-B"}""";

    private Map<JoinType , String> defaultExpectedResults =
        Map.of(
            JoinType.FULL_OUTER, """
                {id:1,leftValue:"L1"}
                {id:2,leftValue:"L2-A",rightValue:"R2-A"}
                {id:2,leftValue:"L2-A",rightValue:"R2-B"}
                {id:2,leftValue:"L2-B",rightValue:"R2-A"}
                {id:2,leftValue:"L2-B",rightValue:"R2-B"}
                {id:3,leftValue:"L3",rightValue:"R3"}
                {id:4,rightValue:"R4"}
                {id:5,leftValue:"L5",rightValue:"R5-A"}
                {id:5,leftValue:"L5",rightValue:"R5-B"}"""
            ,
            JoinType.INNER, """
                {id:2,leftValue:"L2-A",rightValue:"R2-A"}
                {id:2,leftValue:"L2-A",rightValue:"R2-B"}
                {id:2,leftValue:"L2-B",rightValue:"R2-A"}
                {id:2,leftValue:"L2-B",rightValue:"R2-B"}
                {id:3,leftValue:"L3",rightValue:"R3"}
                {id:5,leftValue:"L5",rightValue:"R5-A"}
                {id:5,leftValue:"L5",rightValue:"R5-B"}"""
            ,
            JoinType.LEFT_ANTI, """
                {id:1,leftValue:"L1"}"""
            ,
            JoinType.LEFT_OUTER, """
                {id:1,leftValue:"L1"}
                {id:2,leftValue:"L2-A",rightValue:"R2-A"}
                {id:2,leftValue:"L2-A",rightValue:"R2-B"}
                {id:2,leftValue:"L2-B",rightValue:"R2-A"}
                {id:2,leftValue:"L2-B",rightValue:"R2-B"}
                {id:3,leftValue:"L3",rightValue:"R3"}
                {id:5,leftValue:"L5",rightValue:"R5-A"}
                {id:5,leftValue:"L5",rightValue:"R5-B"}"""
            ,
            JoinType.LEFT_SEMI, """
                {id:2,leftValue:"L2-A"}
                {id:2,leftValue:"L2-A"}
                {id:2,leftValue:"L2-B"}
                {id:2,leftValue:"L2-B"}
                {id:3,leftValue:"L3"}
                {id:5,leftValue:"L5"}
                {id:5,leftValue:"L5"}"""
            ,
            JoinType.RIGHT_ANTI, """
                {id:4,rightValue:"R4"}"""
            ,
            JoinType.RIGHT_OUTER, """
                {id:2,leftValue:"L2-A",rightValue:"R2-A"}
                {id:2,leftValue:"L2-B",rightValue:"R2-A"}
                {id:2,leftValue:"L2-A",rightValue:"R2-B"}
                {id:2,leftValue:"L2-B",rightValue:"R2-B"}
                {id:3,leftValue:"L3",rightValue:"R3"}
                {id:4,rightValue:"R4"}
                {id:5,leftValue:"L5",rightValue:"R5-A"}
                {id:5,leftValue:"L5",rightValue:"R5-B"}"""
            ,
            JoinType.RIGHT_SEMI, """
                {id:2,rightValue:"R2-A"}
                {id:2,rightValue:"R2-A"}
                {id:2,rightValue:"R2-B"}
                {id:2,rightValue:"R2-B"}
                {id:3,rightValue:"R3"}
                {id:5,rightValue:"R5-A"}
                {id:5,rightValue:"R5-B"}"""
    );

    private String tenant = TenantService.MAIN_TENANT;

    /**
     * Prepare Kestra run context by creating ION inputs files from strings.
     * @param leftContent string of left input.
     * @param rightContent string of right input.
     * @return Run context storing files, ready to run task.
     */
    private RunContext getRunContext(String leftContent, String rightContent) {
        return this.runContextFactory.of(Map.of(
            "left", storeContent(leftContent).toString(),
            "right", storeContent(rightContent).toString()
        ));
    }

    /**
     * Store an ION string as a file in Kestra storage.
     * @param content string of input.
     * @return URI of the stored file.
     */
    private URI storeContent(String content) {
        try {
            return this.storageInterface.put(
                tenant,
                null,
                URI.create("/" + IdUtils.create() + ".ion"),
                new ByteArrayInputStream(content.getBytes())
            );
        } catch (IOException e) {
            System.err.println(e.getMessage());
            fail("Unable to make refs files.");
            return null;
        }
    }

    /**
     * Run task with mandatory properties only and return output.
     * Left and right data are not passed as parameters but as files with run context.
     * @see getRunContext()
     * @param joinImplementation
     * @param joinType
     * @param leftKey
     * @param rightKey
     * @return Output object.
     * @throws Exception
     */
    private Join.Output getOutput(RunContext runContext,
            JoinImplementation joinImplementation,
            JoinType joinType,
            String leftKey,
            String rightKey) throws Exception {
        Join task = Join.builder()
            .left(Property.ofExpression("{{ left }}"))
            .right(Property.ofExpression("{{ right }}"))
            .joinImplementation(Property.ofValue(joinImplementation))
            .joinType(Property.ofValue(joinType))
            .leftKey(Property.ofValue(leftKey))
            .rightKey(Property.ofValue(rightKey))
            .build();
        return task.run(runContext);
    }

    /**
     * Run task with labels and output mode properties and return output.
     * Left and right data are not passed as parameters but as files with run context.
     * @see getRunContext()
     * @param joinImplementation
     * @param joinType
     * @param leftKey
     * @param rightKey
     * @param leftLabel
     * @param rightLabel
     * @param outputMode
     * @return Output object.
     * @throws Exception
     */
    private Join.Output getOutput(RunContext runContext,
            JoinImplementation joinImplementation,
            JoinType joinType,
            String leftKey,
            String rightKey,
            String leftLabel,
            String rightLabel,
            OutputMode outputMode) throws Exception {
        Join task = Join.builder()
            .left(Property.ofExpression("{{ left }}"))
            .right(Property.ofExpression("{{ right }}"))
            .joinImplementation(Property.ofValue(joinImplementation))
            .joinType(Property.ofValue(joinType))
            .leftKey(Property.ofValue(leftKey))
            .rightKey(Property.ofValue(rightKey))
            .leftLabel(leftLabel)
            .rightLabel(rightLabel)
            .outputMode(Property.ofValue(outputMode))
            .build();
        return task.run(runContext);
    }

    /**
     * Run task with onlyFirstMatchOnSemi optional property and return output.
     * Left and right data are not passed as parameters but as files with run context.
     * @see getRunContext()
     * @param joinImplementation
     * @param joinType
     * @param leftKey
     * @param rightKey
     * @param onlyFirstMatchOnSemi
     * @return Output object.
     * @throws Exception
     */
    private Join.Output getOutput(RunContext runContext,
            JoinImplementation joinImplementation,
            JoinType joinType,
            String leftKey,
            String rightKey,
            Boolean onlyFirstMatchOnSemi) throws Exception {
        Join task = Join.builder()
            .left(Property.ofExpression("{{ left }}"))
            .right(Property.ofExpression("{{ right }}"))
            .joinImplementation(Property.ofValue(joinImplementation))
            .joinType(Property.ofValue(joinType))
            .leftKey(Property.ofValue(leftKey))
            .rightKey(Property.ofValue(rightKey))
            .onlyFirstMatchOnSemi(Property.ofValue(onlyFirstMatchOnSemi))
            .build();
        return task.run(runContext);
    }

    /**
     * Run task with onMissingKey optional property and return output.
     * Left and right data are not passed as parameters but as files with run context.
     * @see getRunContext()
     * @param joinImplementation
     * @param joinType
     * @param leftKey
     * @param rightKey
     * @param onMissingKey
     * @return Output object.
     * @throws Exception
     */
    private Join.Output getOutput(RunContext runContext,
            JoinImplementation joinImplementation,
            JoinType joinType,
            String leftKey,
            String rightKey,
            MissingKeyAction onMissingKey) throws Exception {
        Join task = Join.builder()
            .left(Property.ofExpression("{{ left }}"))
            .right(Property.ofExpression("{{ right }}"))
            .joinImplementation(Property.ofValue(joinImplementation))
            .joinType(Property.ofValue(joinType))
            .leftKey(Property.ofValue(leftKey))
            .rightKey(Property.ofValue(rightKey))
            .onMissingKey(Property.ofValue(onMissingKey))
            .build();
        return task.run(runContext);
    }

    /**
     * Run task with default keys and no optional properties and return output.
     * Left and right data are not passed as parameters but as files with run context.
     * @see getRunContext()
     * @param joinImplementation
     * @param joinType
     * @return Output object.
     * @throws Exception
     */
    private Join.Output getOutput(RunContext runContext,
            JoinImplementation joinImplementation,
            JoinType joinType) throws Exception {
        return getOutput(runContext,
            joinImplementation,
            joinType,
            defaultKey,
            defaultKey);
    }

    /**
     * Run task with default keys, labels and output mode properties and return output.
     * Left and right data are not passed as parameters but as files with run context.
     * @see getRunContext()
     * @param joinImplementation
     * @param joinType
     * @param leftLabel
     * @param rightLabel
     * @param outputMode
     * @return Output object.
     * @throws Exception
     */
    private Join.Output getOutputWithLabels(RunContext runContext,
            JoinImplementation joinImplementation,
            JoinType joinType,
            String leftLabel,
            String rightLabel,
            OutputMode outputMode) throws Exception {
        return getOutput(runContext,
            joinImplementation,
            joinType,
            defaultKey,
            defaultKey,
            leftLabel,
            rightLabel,
            outputMode);
    }

    /**
     * Run task with default keys and onlyFirstMatchOnSemi optional property and return output.
     * Left and right data are not passed as parameters but as files with run context.
     * @see getRunContext()
     * @param joinImplementation
     * @param joinType
     * @param onlyFirstMatchOnSemi
     * @return Output object.
     * @throws Exception
     */
    private Join.Output getOutputWithOnlyFirstMatchOnSemiOnly(RunContext runContext,
            JoinImplementation joinImplementation,
            JoinType joinType,
            Boolean onlyFirstMatchOnSemi) throws Exception {
        return getOutput(runContext,
            joinImplementation,
            joinType,
            defaultKey,
            defaultKey,
            onlyFirstMatchOnSemi);
    }

    /**
     * Run task with default keys and onMissingKey optional property and return output.
     * Left and right data are not passed as parameters but as files with run context.
     * @see getRunContext()
     * @param joinImplementation
     * @param joinType
     * @param onMissingKey
     * @return Output object.
     * @throws Exception
     */
    private Join.Output getOutput(RunContext runContext,
            JoinImplementation joinImplementation,
            JoinType joinType,
            MissingKeyAction onMissingKey) throws Exception {
        return getOutput(runContext, joinImplementation, joinType, defaultKey, defaultKey, onMissingKey);
    }


    /**
     * Sort lines of ION string.
     * @param content Unsorted content.
     * @return Sorted content.
     */
    private String sortIonLines(String content) {
        return Arrays.stream(content.strip().split("\\R"))
            .filter(line -> !line.isBlank())
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    /*
    ---------------------------------------------------------------------------------------
    Tests of minimum required behaviour with basic usage
    ---------------------------------------------------------------------------------------
    */

    /**
     * Test of join algorithms consistency by checking result data for each join type with basic dataset and no option.
     * Given and expected results are sorted before comparison to eliminate differences of line order.
     * Implementations only affects line order so the expected results doesn't differ according to this setting.
     */
    @Test
    void joinResultsAreConsistent() throws Exception {

        RunContext runContext = getRunContext(defaultLeftContent, defaultRightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                String expected = sortIonLines(defaultExpectedResults.get(joinType));

                try {
                    runOutput = getOutput(runContext, joinImplementation, joinType);
                } catch (Exception e) {
                    fail(e.getMessage() + ", cause: " + e.getCause());
                }

                try (InputStream streamResult = this.storageInterface.get(tenant, null, runOutput.getUri())) {
                    String result = sortIonLines(new String(streamResult.readAllBytes(), StandardCharsets.UTF_8));

                    System.out.println("With " + joinImplementation + ", " + joinType + " join: \n");
                    System.out.println("Got:\n" + result);
                    System.out.println("Expecting:\n" + expected);

                    assertThat("Result should be consistent according to join type", result, equalTo(expected));
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    fail("Unable to load results files.");
                }
            }
        }
    }

    /**
     * Tests that counts outputs are available and correct according to dataset.
     */
    @Test
    void countsAreCorrect() throws Exception {

        long expectedleftSize = defaultLeftContent.split("\n").length;
        long expectedrightSize = defaultRightContent.split("\n").length;

        RunContext runContext = getRunContext(defaultLeftContent, defaultRightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                long expectedResultCount = defaultExpectedResults.get(joinType).split("\n").length;

                try {
                    runOutput = getOutput(runContext, joinImplementation, joinType);
                } catch (Exception e) {
                    fail(e.getMessage() + ", cause: " + e.getCause());
                }

                long outputSize = runOutput.getOutputSize();
                long leftSize = runOutput.getLeftSize();
                long rightSize = runOutput.getRightSize();

                System.out.println("With " + joinImplementation + ", " + joinType + " join: \n");
                System.out.println("Got:\n" + outputSize);
                System.out.println("Expecting:\n" + expectedResultCount);

                assertThat("Output count should be equal to expected result count", outputSize, equalTo(expectedResultCount));
                assertThat("Left count should be correct", leftSize, equalTo(expectedleftSize));
                assertThat("Right count should be correct", rightSize, equalTo(expectedrightSize));
            }
        }
    }

    /**
     * Tests that task don't fail if one or both data input(s) is empty.
     * Empty inputs are not strictly invalid for join algorithms.
     * This test checks that this use case is not broken, for example by other piece of code than join algorithms.
     */
    @Test
    void emptyContentIsValid() throws Exception {

        Map<String, String[]> contentsBySide =
            Map.of(
                "LEFT", new String[] { "", defaultRightContent },
                "RIGHT", new String[] { defaultLeftContent, "" },
                "BOTH", new String[] { "", "" }
            );

        for (var testEntry : contentsBySide.entrySet()) {
            RunContext runContext = getRunContext(testEntry.getValue()[0], testEntry.getValue()[1]);
            Join.Output runOutput = null;

            for (JoinImplementation joinImplementation : JoinImplementation.values()) {
                for (JoinType joinType : JoinType.values()) {
                    Boolean success = null;
                    String errorMessage = "";

                    try {
                        runOutput = getOutput(runContext, joinImplementation, joinType);
                        success = true;
                    } catch (Exception e) {
                        success = false;
                        errorMessage = e.getMessage();
                    }

                    System.out.println("With " + joinImplementation + ", " + joinType + " join, " + testEntry.getKey() + " empty input(s): \n");
                    System.out.println("Got: success: " + success);
                    if (errorMessage != "") System.out.println("Got error message:\n" + errorMessage);
                    System.out.println("Expecting: success: true");

                    assertThat("Should not fail with empty inputs", success, equalTo(true));
                }
            }
        }
    }

    /**
     * Tests that a leftKey or rightKey property rendering to an empty or blank string fails
     * with a render error naming the property, instead of a missing key error or an empty result.
     */
    @Test
    void blankKeyThrowRenderError() throws Exception {

        RunContext runContext = getRunContext(defaultLeftContent, defaultRightContent);

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                for (String blankValue : new String[] { "", "   " }) {
                    Map<String, String[]> keysByProperty =
                        Map.of(
                            "leftKey", new String[] { blankValue, defaultKey },
                            "rightKey", new String[] { defaultKey, blankValue }
                        );

                    for (var testEntry : keysByProperty.entrySet()) {
                        String errorMessage = "";
                        String expectedError = String.format(Messages.BLANK_PROPERTY, testEntry.getKey());

                        try {
                            getOutput(runContext,
                                joinImplementation,
                                joinType,
                                testEntry.getValue()[0],
                                testEntry.getValue()[1]);
                        } catch (Exception e) {
                            errorMessage = e.getMessage();
                        }

                        System.out.println("With " + joinImplementation + ", " + joinType + " join, blank value [" + blankValue + "] on " + testEntry.getKey() + ": \n");
                        System.out.println("Got error message:\n" + errorMessage);
                        System.out.println("Expecting:\n" + expectedError);

                        assertThat("Should show error for blank property", errorMessage, equalTo(expectedError));
                    }
                }
            }
        }
    }

    /**
     * Tests that a left or right property rendering to an empty or blank string fails
     * with a render error naming the property, instead of a storage read error.
     */
    @Test
    void blankInputThrowRenderError() throws Exception {

        String leftFile = storeContent(defaultLeftContent).toString();
        String rightFile = storeContent(defaultRightContent).toString();

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                for (String blankValue : new String[] { "", "   " }) {
                    Map<String, String[]> inputsByProperty =
                        Map.of(
                            "left", new String[] { blankValue, rightFile },
                            "right", new String[] { leftFile, blankValue }
                        );

                    for (var testEntry : inputsByProperty.entrySet()) {
                        String errorMessage = "";
                        String expectedError = String.format(Messages.BLANK_PROPERTY, testEntry.getKey());

                        RunContext runContext = this.runContextFactory.of(Map.of(
                            "left", testEntry.getValue()[0],
                            "right", testEntry.getValue()[1]
                        ));

                        try {
                            getOutput(runContext, joinImplementation, joinType);
                        } catch (Exception e) {
                            errorMessage = e.getMessage();
                        }

                        System.out.println("With " + joinImplementation + ", " + joinType + " join, blank value [" + blankValue + "] on " + testEntry.getKey() + ": \n");
                        System.out.println("Got error message:\n" + errorMessage);
                        System.out.println("Expecting:\n" + expectedError);

                        assertThat("Should show error for blank property", errorMessage, equalTo(expectedError));
                    }
                }
            }
        }
    }

    /**
     * Tests that accentuated characters are preserved.
     */
    @Test
    void handleAccents() throws Exception {

        String leftContent = """
            {id:1,leftValue:"é"}
            {id:2,leftValue:"à"}
            {id:2,leftValue:"ê"}
            {id:3,leftValue:"ç"}
            {id:5,leftValue:"ù"}""";

        String rightContent = """
            {id:2,rightValue:"ã"}
            {id:2,rightValue:"â"}
            {id:3,rightValue:"è"}
            {id:4,rightValue:"ô"}
            {id:5,rightValue:"ï"}
            {id:5,rightValue:"ü"}""";

        Map<JoinType, String> expectedResults =
            Map.of(
                JoinType.FULL_OUTER, """
                    {id:2,leftValue:"à",rightValue:"ã"}
                    {id:2,leftValue:"ê",rightValue:"ã"}
                    {id:2,leftValue:"à",rightValue:"â"}
                    {id:2,leftValue:"ê",rightValue:"â"}
                    {id:3,leftValue:"ç",rightValue:"è"}
                    {id:4,rightValue:"ô"}
                    {id:5,leftValue:"ù",rightValue:"ï"}
                    {id:5,leftValue:"ù",rightValue:"ü"}
                    {id:1,leftValue:"é"}"""
                ,
                JoinType.INNER, """
                    {id:2,leftValue:"à",rightValue:"ã"}
                    {id:2,leftValue:"ê",rightValue:"ã"}
                    {id:2,leftValue:"à",rightValue:"â"}
                    {id:2,leftValue:"ê",rightValue:"â"}
                    {id:3,leftValue:"ç",rightValue:"è"}
                    {id:5,leftValue:"ù",rightValue:"ï"}
                    {id:5,leftValue:"ù",rightValue:"ü"}"""
                ,
                JoinType.LEFT_ANTI, """
                    {id:1,leftValue:"é"}"""
                ,
                JoinType.LEFT_OUTER, """
                    {id:2,leftValue:"à",rightValue:"ã"}
                    {id:2,leftValue:"ê",rightValue:"ã"}
                    {id:2,leftValue:"à",rightValue:"â"}
                    {id:2,leftValue:"ê",rightValue:"â"}
                    {id:3,leftValue:"ç",rightValue:"è"}
                    {id:5,leftValue:"ù",rightValue:"ï"}
                    {id:5,leftValue:"ù",rightValue:"ü"}
                    {id:1,leftValue:"é"}"""
                ,
                JoinType.LEFT_SEMI, """
                    {id:2,leftValue:"à"}
                    {id:2,leftValue:"ê"}
                    {id:2,leftValue:"à"}
                    {id:2,leftValue:"ê"}
                    {id:3,leftValue:"ç"}
                    {id:5,leftValue:"ù"}
                    {id:5,leftValue:"ù"}"""
                ,
                JoinType.RIGHT_ANTI, """
                    {id:4,rightValue:"ô"}"""
                ,
                JoinType.RIGHT_OUTER, """
                    {id:2,leftValue:"à",rightValue:"ã"}
                    {id:2,leftValue:"ê",rightValue:"ã"}
                    {id:2,leftValue:"à",rightValue:"â"}
                    {id:2,leftValue:"ê",rightValue:"â"}
                    {id:3,leftValue:"ç",rightValue:"è"}
                    {id:4,rightValue:"ô"}
                    {id:5,leftValue:"ù",rightValue:"ï"}
                    {id:5,leftValue:"ù",rightValue:"ü"}"""
                ,
                JoinType.RIGHT_SEMI, """
                    {id:2,rightValue:"ã"}
                    {id:2,rightValue:"â"}
                    {id:2,rightValue:"ã"}
                    {id:2,rightValue:"â"}
                    {id:3,rightValue:"è"}
                    {id:5,rightValue:"ï"}
                    {id:5,rightValue:"ü"}"""
            );

        RunContext runContext = getRunContext(leftContent, rightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                String expected = sortIonLines(expectedResults.get(joinType));

                try {
                    runOutput = getOutput(runContext, joinImplementation, joinType);
                } catch (Exception e) {
                    fail(e.getMessage() + ", cause: " + e.getCause());
                }

                try (InputStream streamResult = this.storageInterface.get(tenant, null, runOutput.getUri())) {
                    String result = sortIonLines(new String(streamResult.readAllBytes(), StandardCharsets.UTF_8));

                    System.out.println("With " + joinImplementation + ", " + joinType + " join: \n");
                    System.out.println("Got:\n" + result);
                    System.out.println("Expecting:\n" + expected);

                    assertThat("Result should preserve accents", result, equalTo(expected));
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    fail("Unable to load results files.");
                }
            }
        }
    }

    /*
    ---------------------------------------------------------------------------------------
    Tests of extended features
    ---------------------------------------------------------------------------------------
    */

    /**
     * Test of join with nested keys by using the . sign in keys properties.
     */
    @Test
    void nestedKeysAreManaged() throws Exception {

        String leftContent = """
            {object:{id:1},leftValue:"L1"}
            {object:{id:2},leftValue:"L2-A"}
            {object:{id:2},leftValue:"L2-B"}
            {object:{id:3},leftValue:"L3"}
            {object:{id:5},leftValue:"L5"}""";

        String rightContent = """
            {object:{id:2},rightValue:"R2-A"}
            {object:{id:2},rightValue:"R2-B"}
            {object:{id:3},rightValue:"R3"}
            {object:{id:4},rightValue:"R4"}
            {object:{id:5},rightValue:"R5-A"}
            {object:{id:5},rightValue:"R5-B"}""";

        RunContext runContext = getRunContext(leftContent, rightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                String expected = sortIonLines(defaultExpectedResults.get(joinType));

                try {
                    runOutput = getOutput(runContext, joinImplementation, joinType, "object.id", "object.id");
                } catch (Exception e) {
                    fail(e.getMessage() + ", cause: " + e.getCause());
                }

                try (InputStream streamResult = this.storageInterface.get(tenant, null, runOutput.getUri())) {
                    /*
                    Result is processed to replace nested field to normal one so it can be compared to default expected results
                    */
                    String result = sortIonLines(new String(streamResult.readAllBytes(), StandardCharsets.UTF_8)
                        .replaceAll("object:\\{id:([^}]+)}", "id:$1"));

                    System.out.println("With " + joinImplementation + ", " + joinType + " join: \n");
                    System.out.println("Got:\n" + result);
                    System.out.println("Expecting:\n" + expected);

                    assertThat("Result should be joined by using nested keys", result, equalTo(expected));
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    fail("Unable to load results files.");
                }
            }
        }
    }

    /**
     * Test of fields renaming in result by using prefix output mode, including deduplication of common fields.
     */
    @Test
    void withPrefixesFieldsAreRenamed() throws Exception {

        String leftContent = """
            {id:1,value:"L1"}
            {id:2,value:"L2-A"}
            {id:2,value:"L2-B"}
            {id:3,value:"L3"}
            {id:5,value:"L5"}""";

        String rightContent = """
            {id:2,value:"R2-A"}
            {id:2,value:"R2-B"}
            {id:3,value:"R3"}
            {id:4,value:"R4"}
            {id:5,value:"R5-A"}
            {id:5,value:"R5-B"}""";

        Map<JoinType , String> prefixedExpectedResults =
            Map.of(
                JoinType.FULL_OUTER, """
                    {L_id:2,L_value:"L2-A",R_id:2,R_value:"R2-A"}
                    {L_id:2,L_value:"L2-B",R_id:2,R_value:"R2-A"}
                    {L_id:2,L_value:"L2-A",R_id:2,R_value:"R2-B"}
                    {L_id:2,L_value:"L2-B",R_id:2,R_value:"R2-B"}
                    {L_id:3,L_value:"L3",R_id:3,R_value:"R3"}
                    {R_id:4,R_value:"R4"}
                    {L_id:5,L_value:"L5",R_id:5,R_value:"R5-A"}
                    {L_id:5,L_value:"L5",R_id:5,R_value:"R5-B"}
                    {L_id:1,L_value:"L1"}"""
                ,
                JoinType.INNER, """
                    {L_id:2,L_value:"L2-A",R_id:2,R_value:"R2-A"}
                    {L_id:2,L_value:"L2-B",R_id:2,R_value:"R2-A"}
                    {L_id:2,L_value:"L2-A",R_id:2,R_value:"R2-B"}
                    {L_id:2,L_value:"L2-B",R_id:2,R_value:"R2-B"}
                    {L_id:3,L_value:"L3",R_id:3,R_value:"R3"}
                    {L_id:5,L_value:"L5",R_id:5,R_value:"R5-A"}
                    {L_id:5,L_value:"L5",R_id:5,R_value:"R5-B"}"""
                ,
                JoinType.LEFT_ANTI, """
                    {L_id:1,L_value:"L1"}"""
                ,
                JoinType.LEFT_OUTER, """
                    {L_id:2,L_value:"L2-A",R_id:2,R_value:"R2-A"}
                    {L_id:2,L_value:"L2-B",R_id:2,R_value:"R2-A"}
                    {L_id:2,L_value:"L2-A",R_id:2,R_value:"R2-B"}
                    {L_id:2,L_value:"L2-B",R_id:2,R_value:"R2-B"}
                    {L_id:3,L_value:"L3",R_id:3,R_value:"R3"}
                    {L_id:5,L_value:"L5",R_id:5,R_value:"R5-A"}
                    {L_id:5,L_value:"L5",R_id:5,R_value:"R5-B"}
                    {L_id:1,L_value:"L1"}"""
                ,
                JoinType.LEFT_SEMI, """
                    {L_id:2,L_value:"L2-A"}
                    {L_id:2,L_value:"L2-B"}
                    {L_id:2,L_value:"L2-A"}
                    {L_id:2,L_value:"L2-B"}
                    {L_id:3,L_value:"L3"}
                    {L_id:5,L_value:"L5"}
                    {L_id:5,L_value:"L5"}"""
                ,
                JoinType.RIGHT_ANTI, """
                    {R_id:4,R_value:"R4"}"""
                ,
                JoinType.RIGHT_OUTER, """
                    {L_id:2,L_value:"L2-A",R_id:2,R_value:"R2-A"}
                    {L_id:2,L_value:"L2-B",R_id:2,R_value:"R2-A"}
                    {L_id:2,L_value:"L2-A",R_id:2,R_value:"R2-B"}
                    {L_id:2,L_value:"L2-B",R_id:2,R_value:"R2-B"}
                    {L_id:3,L_value:"L3",R_id:3,R_value:"R3"}
                    {R_id:4,R_value:"R4"}
                    {L_id:5,L_value:"L5",R_id:5,R_value:"R5-A"}
                    {L_id:5,L_value:"L5",R_id:5,R_value:"R5-B"}"""
                ,
                JoinType.RIGHT_SEMI, """
                    {R_id:2,R_value:"R2-A"}
                    {R_id:2,R_value:"R2-A"}
                    {R_id:2,R_value:"R2-B"}
                    {R_id:2,R_value:"R2-B"}
                    {R_id:3,R_value:"R3"}
                    {R_id:5,R_value:"R5-A"}
                    {R_id:5,R_value:"R5-B"}"""
            );

        RunContext runContext = getRunContext(leftContent, rightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                String expected = sortIonLines(prefixedExpectedResults.get(joinType));

                try {
                    runOutput = getOutputWithLabels(runContext, joinImplementation, joinType, "L", "R", OutputMode.PREFIX);
                } catch (Exception e) {
                    fail(e.getMessage() + ", cause: " + e.getCause());
                }

                try (InputStream streamResult = this.storageInterface.get(tenant, null, runOutput.getUri())) {
                    String result = sortIonLines(new String(streamResult.readAllBytes(), StandardCharsets.UTF_8));

                    System.out.println("With " + joinImplementation + ", " + joinType + " join: \n");
                    System.out.println("Got:\n" + result);
                    System.out.println("Expecting:\n" + expected);

                    assertThat("Result should have renamed fields from left and right", result, equalTo(expected));
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    fail("Unable to load results files.");
                }
            }
        }
    }

    /**
     * Test of nest output mode.
     */
    @Test
    void nestOutputMode() throws Exception {

        String leftContent = """
            {id:1,value:"L1"}
            {id:2,value:"L2-A"}
            {id:2,value:"L2-B"}
            {id:3,value:"L3"}
            {id:5,value:"L5"}""";

        String rightContent = """
            {id:2,value:"R2-A"}
            {id:2,value:"R2-B"}
            {id:3,value:"R3"}
            {id:4,value:"R4"}
            {id:5,value:"R5-A"}
            {id:5,value:"R5-B"}""";

        Map<JoinType , String> nestedExpectedResults =
            Map.of(
                JoinType.FULL_OUTER, """
                    {left:{id:2,value:"L2-A"},right:{id:2,value:"R2-A"}}
                    {left:{id:2,value:"L2-B"},right:{id:2,value:"R2-A"}}
                    {left:{id:2,value:"L2-A"},right:{id:2,value:"R2-B"}}
                    {left:{id:2,value:"L2-B"},right:{id:2,value:"R2-B"}}
                    {left:{id:3,value:"L3"},right:{id:3,value:"R3"}}
                    {right:{id:4,value:"R4"}}
                    {left:{id:5,value:"L5"},right:{id:5,value:"R5-A"}}
                    {left:{id:5,value:"L5"},right:{id:5,value:"R5-B"}}
                    {left:{id:1,value:"L1"}}"""
                ,
                JoinType.INNER, """
                    {left:{id:2,value:"L2-A"},right:{id:2,value:"R2-A"}}
                    {left:{id:2,value:"L2-B"},right:{id:2,value:"R2-A"}}
                    {left:{id:2,value:"L2-A"},right:{id:2,value:"R2-B"}}
                    {left:{id:2,value:"L2-B"},right:{id:2,value:"R2-B"}}
                    {left:{id:3,value:"L3"},right:{id:3,value:"R3"}}
                    {left:{id:5,value:"L5"},right:{id:5,value:"R5-A"}}
                    {left:{id:5,value:"L5"},right:{id:5,value:"R5-B"}}"""
                ,
                JoinType.LEFT_ANTI, """
                    {left:{id:1,value:"L1"}}"""
                ,
                JoinType.LEFT_OUTER, """
                    {left:{id:2,value:"L2-A"},right:{id:2,value:"R2-A"}}
                    {left:{id:2,value:"L2-B"},right:{id:2,value:"R2-A"}}
                    {left:{id:2,value:"L2-A"},right:{id:2,value:"R2-B"}}
                    {left:{id:2,value:"L2-B"},right:{id:2,value:"R2-B"}}
                    {left:{id:3,value:"L3"},right:{id:3,value:"R3"}}
                    {left:{id:5,value:"L5"},right:{id:5,value:"R5-A"}}
                    {left:{id:5,value:"L5"},right:{id:5,value:"R5-B"}}
                    {left:{id:1,value:"L1"}}"""
                ,
                JoinType.LEFT_SEMI, """
                    {left:{id:2,value:"L2-A"}}
                    {left:{id:2,value:"L2-B"}}
                    {left:{id:2,value:"L2-A"}}
                    {left:{id:2,value:"L2-B"}}
                    {left:{id:3,value:"L3"}}
                    {left:{id:5,value:"L5"}}
                    {left:{id:5,value:"L5"}}"""
                ,
                JoinType.RIGHT_ANTI, """
                    {right:{id:4,value:"R4"}}"""
                ,
                JoinType.RIGHT_OUTER, """
                    {left:{id:2,value:"L2-A"},right:{id:2,value:"R2-A"}}
                    {left:{id:2,value:"L2-B"},right:{id:2,value:"R2-A"}}
                    {left:{id:2,value:"L2-A"},right:{id:2,value:"R2-B"}}
                    {left:{id:2,value:"L2-B"},right:{id:2,value:"R2-B"}}
                    {left:{id:3,value:"L3"},right:{id:3,value:"R3"}}
                    {right:{id:4,value:"R4"}}
                    {left:{id:5,value:"L5"},right:{id:5,value:"R5-A"}}
                    {left:{id:5,value:"L5"},right:{id:5,value:"R5-B"}}"""
                ,
                JoinType.RIGHT_SEMI, """
                    {right:{id:2,value:"R2-A"}}
                    {right:{id:2,value:"R2-A"}}
                    {right:{id:2,value:"R2-B"}}
                    {right:{id:2,value:"R2-B"}}
                    {right:{id:3,value:"R3"}}
                    {right:{id:5,value:"R5-A"}}
                    {right:{id:5,value:"R5-B"}}"""
            );

        RunContext runContext = getRunContext(leftContent, rightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                String expected = sortIonLines(nestedExpectedResults.get(joinType));

                try {
                    runOutput = getOutputWithLabels(runContext, joinImplementation, joinType, "left", "right", OutputMode.NEST);
                } catch (Exception e) {
                    fail(e.getMessage() + ", cause: " + e.getCause());
                }

                try (InputStream streamResult = this.storageInterface.get(tenant, null, runOutput.getUri())) {
                    String result = sortIonLines(new String(streamResult.readAllBytes(), StandardCharsets.UTF_8));

                    System.out.println("With " + joinImplementation + ", " + joinType + " join: \n");
                    System.out.println("Got:\n" + result);
                    System.out.println("Expecting:\n" + expected);

                    assertThat("Result should be nested", result, equalTo(expected));
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    fail("Unable to load results files.");
                }
            }
        }
    }

    /**
     * Test of the 'onlyFirstMatchOnSemi' option property as true.
     * This should prevent duplicate lines in result with semi joins.
     */
    @Test
    void onlyFirstMatchOnSemiOptionIsManaged() throws Exception {

        Map<JoinType , String> onlyFirstMatchExpectedResults =
            Map.of(
                JoinType.LEFT_SEMI, """
                    {id:2,leftValue:"L2-A"}
                    {id:2,leftValue:"L2-B"}
                    {id:3,leftValue:"L3"}
                    {id:5,leftValue:"L5"}"""
                ,
                JoinType.RIGHT_SEMI, """
                    {id:2,rightValue:"R2-A"}
                    {id:2,rightValue:"R2-B"}
                    {id:3,rightValue:"R3"}
                    {id:5,rightValue:"R5-A"}
                    {id:5,rightValue:"R5-B"}"""
            );

        JoinType[] semiJoinTypes = {
            JoinType.LEFT_SEMI,
            JoinType.RIGHT_SEMI
        };

        RunContext runContext = getRunContext(defaultLeftContent, defaultRightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : semiJoinTypes) {
                String expected = sortIonLines(onlyFirstMatchExpectedResults.get(joinType));

                try {
                    runOutput = getOutputWithOnlyFirstMatchOnSemiOnly(runContext, joinImplementation, joinType, true);
                } catch (Exception e) {
                    fail(e.getMessage() + ", cause: " + e.getCause());
                }

                try (InputStream streamResult = this.storageInterface.get(tenant, null, runOutput.getUri())) {
                    String result = sortIonLines(new String(streamResult.readAllBytes(), StandardCharsets.UTF_8));

                    System.out.println("With " + joinImplementation + ", " + joinType + " join: \n");
                    System.out.println("Got:\n" + result);
                    System.out.println("Expecting:\n" + expected);

                    assertThat("Result should have only first matches with semi joins by using corresponding option", result, equalTo(expected));
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    fail("Unable to load results files.");
                }
            }
        }
    }

    /**
     * Tests that task don't fail if onMissingKey option is IGNORE, and null values never match.
     */
    @Test
    void ignoredMissingKey() throws Exception {

        String leftContent = """
            {id:1,leftValue:"L1"}
            {id:null,leftValue:"L2-A"}
            {id:2,leftValue:"L2-B"}
            {leftValue:"L3"}
            {id:5,leftValue:"L5"}""";

        String rightContent = """
            {id:2,rightValue:"R2-A"}
            {id:null,rightValue:"R2-B"}
            {id:3,rightValue:"R3"}
            {id:4,rightValue:"R4"}
            {id:5,rightValue:"R5-A"}
            {id:5,rightValue:"R5-B"}""";

        Map<JoinType , String> expectedResults =
            Map.of(
                JoinType.FULL_OUTER, """
                    {id:1,leftValue:"L1"}
                    {id:2,leftValue:"L2-B",rightValue:"R2-A"}
                    {id:3,rightValue:"R3"}
                    {id:4,rightValue:"R4"}
                    {id:5,leftValue:"L5",rightValue:"R5-A"}
                    {id:5,leftValue:"L5",rightValue:"R5-B"}
                    {id:null,leftValue:"L2-A"}
                    {leftValue:"L3"}
                    {id:null,rightValue:"R2-B"}"""
                ,
                JoinType.INNER, """
                    {id:2,leftValue:"L2-B",rightValue:"R2-A"}
                    {id:5,leftValue:"L5",rightValue:"R5-A"}
                    {id:5,leftValue:"L5",rightValue:"R5-B"}"""
                ,
                JoinType.LEFT_ANTI, """
                    {leftValue:"L3"}
                    {id:null,leftValue:"L2-A"}
                    {id:1,leftValue:"L1"}"""
                ,
                JoinType.LEFT_OUTER, """
                    {id:2,leftValue:"L2-B",rightValue:"R2-A"}
                    {id:5,leftValue:"L5",rightValue:"R5-A"}
                    {id:5,leftValue:"L5",rightValue:"R5-B"}
                    {leftValue:"L3"}
                    {id:null,leftValue:"L2-A"}
                    {id:1,leftValue:"L1"}"""
                ,
                JoinType.LEFT_SEMI, """
                    {id:2,leftValue:"L2-B"}
                    {id:5,leftValue:"L5"}
                    {id:5,leftValue:"L5"}"""
                ,
                JoinType.RIGHT_ANTI, """
                    {id:null,rightValue:"R2-B"}
                    {id:3,rightValue:"R3"}
                    {id:4,rightValue:"R4"}"""
                ,
                JoinType.RIGHT_OUTER, """
                    {id:2,leftValue:"L2-B",rightValue:"R2-A"}
                    {id:3,rightValue:"R3"}
                    {id:4,rightValue:"R4"}
                    {id:5,leftValue:"L5",rightValue:"R5-A"}
                    {id:5,leftValue:"L5",rightValue:"R5-B"}
                    {id:null,rightValue:"R2-B"}"""
                ,
                JoinType.RIGHT_SEMI, """
                    {id:2,rightValue:"R2-A"}
                    {id:5,rightValue:"R5-A"}
                    {id:5,rightValue:"R5-B"}"""
            );

        RunContext runContext = getRunContext(leftContent, rightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                String expected = sortIonLines(expectedResults.get(joinType));

                try {
                    runOutput = getOutput(runContext, joinImplementation, joinType, MissingKeyAction.IGNORE);
                } catch (Exception e) {
                    fail(e.getMessage() + ", cause: " + e.getCause());
                }

                try (InputStream streamResult = this.storageInterface.get(tenant, null, runOutput.getUri())) {
                    String result = sortIonLines(new String(streamResult.readAllBytes(), StandardCharsets.UTF_8));

                    System.out.println("With " + joinImplementation + ", " + joinType + " join: \n");
                    System.out.println("Got:\n" + result);
                    System.out.println("Expecting:\n" + expected);

                    assertThat("Result should have no match between null values", result, equalTo(expected));
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    fail("Unable to load results files.");
                }
            }
        }
    }

    /*
    ---------------------------------------------------------------------------------------
    Tests of behaviour with inconsistent inputs
    ---------------------------------------------------------------------------------------
    */

    /**
     * Tests that task fails if key field is missing in one or both data input(s).
     */
    @Test
    void missingKeyFailsByDefault() throws Exception {

        String leftContent = """
            {id:1,leftValue:"L1"}
            {id:null,leftValue:"L2-A"}
            {id:2,leftValue:"L2-B"}
            {id:3,leftValue:"L3"}
            {id:5,leftValue:"L5"}""";

        String rightContent = """
            {rightValue:"R2-A"}
            {id:2,rightValue:"R2-B"}
            {id:3,rightValue:"R3"}
            {id:4,rightValue:"R4"}
            {id:5,rightValue:"R5-A"}
            {id:5,rightValue:"R5-B"}""";

        Map<String, String[]> contentsBySide =
            Map.of(
                "LEFT", new String[] { leftContent, defaultRightContent },
                "RIGHT", new String[] { defaultLeftContent, rightContent },
                "BOTH", new String[] { leftContent, rightContent }
            );

        for (var testEntry : contentsBySide.entrySet()) {
            RunContext runContext = getRunContext(testEntry.getValue()[0], testEntry.getValue()[1]);
            Join.Output runOutput = null;

            for (JoinImplementation joinImplementation : JoinImplementation.values()) {
                for (JoinType joinType : JoinType.values()) {
                    String errorMessage = "";
                    String expectedErrorPattern = String.format(Messages.MISSING_KEY, "id", "(LEFT|RIGHT|BOTH)", ".*");

                    try {
                        runOutput = getOutput(runContext, joinImplementation, joinType);
                    } catch (Exception e) {
                        errorMessage = e.getMessage();
                    }

                    System.out.println("With " + joinImplementation + ", " + joinType + " join, " + testEntry.getKey() + " missing key:\n");
                    System.out.println("Got error message:\n" + errorMessage);
                    System.out.println("Expecting error pattern:\n" + expectedErrorPattern);

                    assertThat("Should show specific error", errorMessage, matchesPattern(expectedErrorPattern));
                }
            }
        }
    }

    /**
     * Tests invalid types on key fields of data.
     * Should throw a specific error.
     */
    @Test
    void invalidTypeThrowError() throws Exception {

        String leftContent = """
            {id:{{OiBTIKUgTyAASb8=}},leftValue:"L1"}
            {id:2,leftValue:"L2-A"}
            {id:2,leftValue:"L2-B"}
            {id:3,leftValue:"L3"}
            {id:5,leftValue:"L5"}""";

        String rightContent = """
            {id:2,rightValue:"R2-A"}
            {id:[80, 85, 90],rightValue:"R2-B"}
            {id:3,rightValue:"R3"}
            {id:4,rightValue:"R4"}
            {id:5,rightValue:"R5-A"}
            {id:5,rightValue:"R5-B"}""";

        Map<String, String[]> contentsBySide =
            Map.of(
                "LEFT", new String[] { leftContent, defaultRightContent },
                "RIGHT", new String[] { defaultLeftContent, rightContent }
            );

        for (var testEntry : contentsBySide.entrySet()) {
            RunContext runContext = getRunContext(testEntry.getValue()[0], testEntry.getValue()[1]);
            Join.Output runOutput = null;

            for (JoinImplementation joinImplementation : JoinImplementation.values()) {
                for (JoinType joinType : JoinType.values()) {
                    String errorMessage = "";
                    String expectedError = String.format(Messages.UNPROCESSABLE_KEY_TYPE, "id", testEntry.getKey());

                    try {
                        runOutput = getOutput(runContext, joinImplementation, joinType);
                    } catch (Exception e) {
                        errorMessage = e.getMessage();
                    }

                    System.out.println("With " + joinImplementation + ", " + joinType + " join, invalid type on " + testEntry.getKey() + "\n");
                    System.out.println("Got error message:\n" + errorMessage);
                    System.out.println("Expecting error message:\n" + expectedError);

                    assertThat("Should show specific error", errorMessage, equalTo(expectedError));
                }
            }
        }
    }

    /**
     * Tests different types on key fields of same data.
     * Only sorted merge implementation must fail because of impossible sort.
     */
    @Test
    void differentTypesFromSameSideBreaksSortedMergeOnly() throws Exception {

        String leftContent = """
            {id:1,leftValue:"L1"}
            {id:true,leftValue:"L2-A"}
            {id:true,leftValue:"L2-B"}
            {id:3,leftValue:"L3"}
            {id:5,leftValue:"L5"}""";

        String rightContent = """
            {id:true,rightValue:"R2-A"}
            {id:true,rightValue:"R2-B"}
            {id:3,rightValue:"R3"}
            {id:4,rightValue:"R4"}
            {id:5,rightValue:"R5-A"}
            {id:5,rightValue:"R5-B"}""";

        Map<String, String[]> contentsBySide =
            Map.of(
                "LEFT", new String[] { leftContent, defaultRightContent },
                "RIGHT", new String[] { defaultLeftContent, rightContent }
            );

        for (var testEntry : contentsBySide.entrySet()) {
            RunContext runContext = getRunContext(testEntry.getValue()[0], testEntry.getValue()[1]);
            Join.Output runOutput = null;

            for (JoinImplementation joinImplementation : JoinImplementation.values()) {
                for (JoinType joinType : JoinType.values()) {
                    Boolean success = null;
                    String errorMessage = "";
                    String expectedErrorPattern = Messages.DIFFERENT_TYPES.replaceAll("%s", ".*");

                    try {
                        runOutput = getOutput(runContext, joinImplementation, joinType);
                        success = true;
                    } catch (Exception e) {
                        success = false;
                        errorMessage = e.getMessage();
                    }

                    System.out.println("With " + joinImplementation + ", " + joinType + " join, invalid type on " + testEntry.getKey() + "\n");

                    if (joinImplementation == JoinImplementation.SORTED_MERGE) {
                        System.out.println("Got error message:\n" + errorMessage);
                        System.out.println("Expecting error message pattern:\n" + expectedErrorPattern);

                        assertThat("Should show specific error", errorMessage, matchesPattern(expectedErrorPattern));
                    } else {
                        System.out.println("Got: success: " + success);
                        if (errorMessage != "") System.out.println("Got error message:\n" + errorMessage);
                        System.out.println("Expecting: success: true");

                        assertThat("Should not fail for other implementations than sorted merge", success, equalTo(true));
                    }
                }
            }
        }
    }

    /**
     * Tests different types on key fields between data.
     * Only sorted merge implementation must fail because of impossible join.
     */
    @Test
    void differentTypesBetweenSidesBreaksSortedMergeOnly() throws Exception {

        String rightContent = """
            {id:2.0,rightValue:"R2-A"}
            {id:2.0,rightValue:"R2-B"}
            {id:3.5,rightValue:"R3"}
            {id:4.2,rightValue:"R4"}
            {id:5.1,rightValue:"R5-A"}
            {id:5.0,rightValue:"R5-B"}""";

        RunContext runContext = getRunContext(defaultLeftContent, rightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                Boolean success = null;
                String errorMessage = "";
                String expectedErrorPattern = Messages.DIFFERENT_TYPES.replaceAll("%s", ".*");

                try {
                    runOutput = getOutput(runContext, joinImplementation, joinType);
                    success = true;
                } catch (Exception e) {
                    success = false;
                    errorMessage = e.getMessage();
                }

                System.out.println("With " + joinImplementation + ", " + joinType + " join:\n");

                if (joinImplementation == JoinImplementation.SORTED_MERGE) {
                    System.out.println("Got error message:\n" + errorMessage);
                    System.out.println("Expecting error message pattern:\n" + expectedErrorPattern);

                    assertThat("Should show specific error", errorMessage, matchesPattern(expectedErrorPattern));
                } else {
                    System.out.println("Got: success: " + success);
                    if (errorMessage != "") System.out.println("Got error message:\n" + errorMessage);
                    System.out.println("Expecting: success: true");

                    assertThat("Should not fail for other implementations than sorted merge", success, equalTo(true));
                }
            }
        }
    }

    /**
     * Test of keys from where marked depth (with .) doesn't match an existent object depth in data.
     * Should throw a specific error.
     */
    @Test
    void unreachableKeyDepthThrowError() throws Exception {

        Map<String, String[]> testKeysBySide =
            Map.of(
                "LEFT", new String[] { "id.x", defaultKey },
                "RIGHT", new String[] { defaultKey, "id.x" }
            );

        RunContext runContext = getRunContext(defaultLeftContent, defaultRightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                for (var testEntry : testKeysBySide.entrySet()) {
                    String errorMessage = "";
                    String expectedError = String.format(Messages.UNREACHABLE_KEY_DEPTH, "id.x", testEntry.getKey());

                    try {
                        runOutput = getOutput(runContext, joinImplementation, joinType, testEntry.getValue()[0], testEntry.getValue()[1]);
                    } catch (Exception e) {
                        errorMessage = e.getMessage();
                    }

                    System.out.println("With " + joinImplementation + ", " + joinType + " join, " + testEntry.getKey() + " invalid key: \n");
                    System.out.println("Got error message:\n" + errorMessage);
                    System.out.println("Expecting error message:\n" + expectedError);

                    assertThat("Should show specific error", errorMessage, equalTo(expectedError));
                }
            }
        }
    }

    /**
     * Tests the case where key match an object in data instead of a raw value.
     * Should throw a specific error.
     */
    @Test
    void objectKeyThrowError() throws Exception {

        String leftContent = """
            {object:{id:1},leftValue:"L1"}
            {object:{id:2},leftValue:"L2-A"}
            {object:{id:2},leftValue:"L2-B"}
            {object:{id:3},leftValue:"L3"}
            {object:{id:5},leftValue:"L5"}""";

        String rightContent = """
            {object:{id:2},rightValue:"R2-A"}
            {object:{id:2},rightValue:"R2-B"}
            {object:{id:3},rightValue:"R3"}
            {object:{id:4},rightValue:"R4"}
            {object:{id:5},rightValue:"R5-A"}
            {object:{id:5},rightValue:"R5-B"}""";

        Map<String, String[]> testKeysBySide =
            Map.of(
                "LEFT", new String[] { "object", "object.id" },
                "RIGHT", new String[] { "object.id", "object" }
            );

        RunContext runContext = getRunContext(leftContent, rightContent);
        Join.Output runOutput = null;

        for (JoinImplementation joinImplementation : JoinImplementation.values()) {
            for (JoinType joinType : JoinType.values()) {
                for (var testEntry : testKeysBySide.entrySet()) {
                    String errorMessage = "";
                    String expectedError = String.format(Messages.KEY_VALUE_IS_OBJECT, "object", testEntry.getKey());

                    try {
                        runOutput = getOutput(runContext, joinImplementation, joinType, testEntry.getValue()[0], testEntry.getValue()[1]);
                    } catch (Exception e) {
                        errorMessage = e.getMessage();
                    }

                    System.out.println("With " + joinImplementation + ", " + joinType + " join, " + testEntry.getKey() + " invalid key: \n");
                    System.out.println("Got error message:\n" + errorMessage);
                    System.out.println("Expecting error message:\n" + expectedError);

                    assertThat("Should show specific error", errorMessage, equalTo(expectedError));
                }
            }
        }
    }
}
