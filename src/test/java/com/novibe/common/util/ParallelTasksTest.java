package com.novibe.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelTasksTest {

    @Test
    void keepsResultsInInputOrder() {
        List<String> results = ParallelTasks.callAll(List.of(3, 1, 2), number -> "answer " + number);

        assertEquals(List.of("answer 3", "answer 1", "answer 2"), results);
    }

    @Test
    void runsEveryInputOnItsOwnVirtualThread() {
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

        ParallelTasks.runAll(List.of(1, 2, 3), number -> threads.add(Thread.currentThread()));

        assertEquals(3, threads.size());
        assertTrue(threads.stream().allMatch(Thread::isVirtual));
    }

    @Test
    void failsWithWhatTheTaskThrew() {
        IllegalStateException thrown = new IllegalStateException("task said no");

        StructuredTaskScope.FailedException exception = assertThrows(StructuredTaskScope.FailedException.class,
                () -> ParallelTasks.callAll(List.of(1), number -> {
                    throw thrown;
                }));

        assertEquals(thrown, exception.getCause());
    }

    @Test
    void takesNoTasksForAnEmptyInput() {
        assertTrue(ParallelTasks.callAll(List.of(), input -> "never happens").isEmpty());
    }

    @Test
    void failsOnlyOnceWhenEveryTaskFails() {
        StructuredTaskScope.FailedException exception = assertThrows(StructuredTaskScope.FailedException.class,
                () -> ParallelTasks.runAll(List.of(1, 2, 3), number -> {
                    throw new IllegalStateException("task " + number + " said no");
                }));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }
}
