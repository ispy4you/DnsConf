package com.novibe.common.util;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs the same call for every input at once on virtual threads, so the preview api stays in one place.
 * The first failing task cancels the rest, and what it threw comes out of the call wrapped in
 * {@link StructuredTaskScope.FailedException}.
 */
public interface ParallelTasks {

    @SuppressWarnings("preview")
    static <T, R> List<R> callAll(Collection<T> inputs, Function<T, R> call) {
        try (var scope = StructuredTaskScope.open()) {
            List<StructuredTaskScope.Subtask<R>> tasks = inputs.stream()
                    .map(input -> scope.fork(() -> call.apply(input)))
                    .toList();
            scope.join();
            return tasks.stream().map(StructuredTaskScope.Subtask::get).toList();
        } catch (InterruptedException interruptedException) {
            throw new RuntimeException(interruptedException);
        }
    }

    static <T> void runAll(Collection<T> inputs, Consumer<T> action) {
        callAll(inputs, input -> {
            action.accept(input);
            return null;
        });
    }

}
