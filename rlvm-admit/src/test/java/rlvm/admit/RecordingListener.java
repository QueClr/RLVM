package rlvm.admit;

import java.util.ArrayList;
import java.util.List;

import rlvm.api.Reason;

/** Records the callbacks of the environment pass as strings, in order. */
final class RecordingListener implements EnvBuilder.Listener {
    final List<String> events = new ArrayList<>();
    int failedUnit = -1;
    Reason failReason;
    String failMessage;
    int frontier;

    @Override
    public void unitAdmitted(int unit) {
        events.add("admitted " + unit);
    }

    @Override
    public void unitFailed(int unit, Reason reason, String message) {
        events.add("failed " + unit + " " + reason);
        failedUnit = unit;
        failReason = reason;
        failMessage = message;
    }

    @Override
    public void frontierAdvanced(int units) {
        events.add("frontier " + units);
        frontier = units;
    }
}
