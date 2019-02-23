package com.enginehub.piston.converter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Converts user input into an actual type. It can provide multiple
 * results per argument.
 *
 * @param <T> the type of the result
 */
public interface ArgumentConverter<T> {

    /**
     * Converts the argument input to a collection of argument values.
     *
     * @param argument the argument input to convert
     * @return the argument values
     */
    Collection<T> convert(String argument);

    /**
     * Describe the arguments that can be provided to this converter.
     *
     * <p>
     * This information is displayed to the user.
     * </p>
     *
     * @return a description of acceptable arguments
     */
    String describeAcceptableArguments();

    /**
     * Given {@code input} as the current input, provide some suggestions for the user.
     *
     * @param input the user's current input
     * @return suggestions for the user
     */
    default List<String> getSuggestions(String input) {
        return Collections.emptyList();
    }

}
