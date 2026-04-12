/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026, 2026, Red Hat Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.test.jfr;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.oracle.svm.core.jfr.JfrEvent;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

public class TestVirtualThreadsExecutionSample extends JfrRecordingTest {
    private static final Duration SAMPLE_PERIOD = Duration.ofMillis(10);
    private static final long WAIT_FOR_SAMPLES_MILLIS = 500;

    private final AtomicLong sampledVirtualThreadId = new AtomicLong();
    private Instant rotationTime;

    @Test
    public void testVirtualThreadExecutionSample() throws Throwable {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread thread = startBusyVirtualThread(stop);
        Recording recording = startExecutionSampleRecording();

        Thread.sleep(WAIT_FOR_SAMPLES_MILLIS);

        stop.set(true);
        thread.join();
        stopRecording(recording, this::validateExecutionSamples);
    }

    @Test
    public void testVirtualThreadExecutionSampleAfterChunkRotation() throws Throwable {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread thread = startBusyVirtualThread(stop);
        Recording recording = startExecutionSampleRecording();

        Thread.sleep(WAIT_FOR_SAMPLES_MILLIS);
        recording.dump(createTempJfrFile());
        rotationTime = Instant.now();
        Thread.sleep(WAIT_FOR_SAMPLES_MILLIS);

        stop.set(true);
        thread.join();
        stopRecording(recording, this::validateExecutionSamplesAfterRotation);
    }

    private Recording startExecutionSampleRecording() throws Throwable {
        Map<String, String> settings = new HashMap<>();
        settings.put("flush-interval", "0");

        String[] events = new String[]{JfrEvent.ExecutionSample.getName()};
        Recording recording = prepareRecording(events, getDefaultConfiguration(), settings, createTempJfrFile());
        recording.enable(JfrEvent.ExecutionSample.getName()).withPeriod(SAMPLE_PERIOD);
        recording.start();
        return recording;
    }

    private Thread startBusyVirtualThread(AtomicBoolean stop) throws Throwable {
        AtomicBoolean started = new AtomicBoolean(false);
        Thread thread = Thread.ofVirtual().start(() -> {
            sampledVirtualThreadId.set(Thread.currentThread().threadId());
            started.set(true);
            spinUntil(stop);
        });
        waitUntilTrue(started::get);
        return thread;
    }

    private void validateExecutionSamples(List<RecordedEvent> events) {
        validateExecutionSamples(events, false);
    }

    private void validateExecutionSamplesAfterRotation(List<RecordedEvent> events) {
        assertNotNull("Chunk rotation marker must be recorded.", rotationTime);
        validateExecutionSamples(events, true);
    }

    private void validateExecutionSamples(List<RecordedEvent> events, boolean afterRotationOnly) {
        long expectedThreadId = sampledVirtualThreadId.get();
        assertTrue(expectedThreadId > 0);

        int matchingEvents = 0;
        for (RecordedEvent event : events) {
            if (afterRotationOnly && !event.getEndTime().isAfter(rotationTime)) {
                continue;
            }

            if (!containsBusyVirtualThreadFrame(event)) {
                continue;
            }

            RecordedThread sampledThread = event.getThread("sampledThread");
            assertNotNull("ExecutionSample is missing sampledThread data.", sampledThread);
            assertTrue("ExecutionSample resolved the wrong sampledThread.", sampledThread.getJavaThreadId() == expectedThreadId);
            matchingEvents++;
        }

        String expectation = afterRotationOnly ? "post-rotation " : "";
        assertTrue("Expected at least one " + expectation + "ExecutionSample event for the virtual thread.", matchingEvents > 0);
    }

    private static boolean containsBusyVirtualThreadFrame(RecordedEvent event) {
        RecordedStackTrace stackTrace = event.getStackTrace();
        assertNotNull("ExecutionSample is missing a stack trace.", stackTrace);

        for (RecordedFrame frame : stackTrace.getFrames()) {
            if (frame.getMethod().getType().getName().equals(TestVirtualThreadsExecutionSample.class.getName()) &&
                            frame.getMethod().getName().equals("spinUntil")) {
                return true;
            }
        }
        return false;
    }

    private static void spinUntil(AtomicBoolean stop) {
        long value = 0;
        while (!stop.get()) {
            value++;
            if ((value & 0xFF) == 0) {
                Thread.onSpinWait();
            }
        }
        assertTrue(value > 0);
    }
}
