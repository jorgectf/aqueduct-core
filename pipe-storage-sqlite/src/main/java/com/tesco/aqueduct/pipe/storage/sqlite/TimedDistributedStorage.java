package com.tesco.aqueduct.pipe.storage.sqlite;

import com.tesco.aqueduct.pipe.api.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.OptionalLong;

public class TimedDistributedStorage implements DistributedStorage {
    private final DistributedStorage storage;
    private final Timer readTimer;
    private final Timer latestOffsetTimer;
    private final Timer writeMessageTimer;
    private final Timer writeMessagesTimer;
    private final Timer writePipeStatetimer;
    private final Timer readPipeStateTimer;

    public TimedDistributedStorage(final DistributedStorage storage, final MeterRegistry meterRegistry) {
        this.storage = storage;
        readTimer = meterRegistry.timer("pipe.storage.read");
        latestOffsetTimer = meterRegistry.timer("pipe.storage.latestOffset");
        writeMessageTimer = meterRegistry.timer("pipe.storage.writeMessage");
        writeMessagesTimer = meterRegistry.timer("pipe.storage.writeMessages");
        writePipeStatetimer = meterRegistry.timer("pipe.storage.writePipeState");
        readPipeStateTimer = meterRegistry.timer("pipe.storage.readPipeState");
    }

    @Override
    public MessageResults read(final List<String> types, final long offset, final List<String> locationUuids) {
        return readTimer.record(() -> storage.read(types, offset, locationUuids));
    }

    @Override
    public long getLatestOffsetMatching(final List<String> types) {
        return latestOffsetTimer.record(() -> storage.getLatestOffsetMatching(types));
    }

    @Override
    public OptionalLong getOffset(OffsetName offsetName) {
        return latestOffsetTimer.record(() -> storage.getOffset(offsetName));
    }

    @Override
    public void write(final Iterable<Message> messages) {
        writeMessageTimer.record(() -> storage.write(messages));
    }

    @Override
    public void write(final Message message) {
        writeMessagesTimer.record(() -> storage.write(message));
    }

    @Override
    public void write(OffsetEntity offset) {
        writeMessagesTimer.record(() -> storage.write(offset));
    }

    @Override
    public void write(PipeState pipeState) {
        writePipeStatetimer.record(() -> storage.write(pipeState));
    }

    @Override
    public void deleteAll() {
        storage.deleteAll();
    }

    @Override
    public PipeState getPipeState() {
        return readPipeStateTimer.record(storage::getPipeState);
    }
}
