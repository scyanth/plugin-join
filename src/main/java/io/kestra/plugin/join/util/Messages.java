package io.kestra.plugin.join.util;

/**
 * Messages for exceptions, logs and testing.
 */
public class Messages {

    private Messages() {}

    public static String RENDER_ERROR = "An exception occurred while trying to render '%s' property.";
    public static String BLANK_PROPERTY = "The '%s' property rendered to a blank value.";
    public static String READ_ERROR = "An exception occurred while trying to read '%s' input.";
    public static String EMPTY_INPUT = "The %s input is empty.";
    public static String MISSING_KEY = "The key field '%s' is missing in the %s input at the following row: %s";
    public static String DIFFERENT_TYPES = "Impossible to compare the values %s and %s because they don't have the same type.";
    public static String UNPROCESSABLE_COMPARED_TYPES = "Impossible to compare the values because their type cannot be processed.";
    public static String UNPROCESSABLE_KEY_TYPE = "The type of value for key '%s' from %s input cannot be processed.";
    public static String UNREACHABLE_KEY_DEPTH = "The key depth in '%s' for %s input is not reachable.";
    public static String KEY_VALUE_IS_OBJECT = "The value for key '%s' from %s input is an object. Hint: the key may need to be nested or deeper.";
    public static String WRITE_ERROR = "An exception occured while trying to write %s in temporary output file.";
    public static String STORAGE_ERROR = "An exception occured while trying to store output file.";
}
