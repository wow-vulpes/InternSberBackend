package ru.kkd.application;

import ru.kkd.domain.model.RunStatus;

public class RunNotCompletedException extends RuntimeException {

    public RunNotCompletedException(String id, RunStatus status) {
        super("Run is not completed: " + id + " (" + status + ")");
    }
}
