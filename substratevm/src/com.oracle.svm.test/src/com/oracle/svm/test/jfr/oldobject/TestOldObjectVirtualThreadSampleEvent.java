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

package com.oracle.svm.test.jfr.oldobject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.word.impl.Word;
import org.junit.Test;

import com.oracle.svm.core.jfr.SubstrateJVM;
import com.oracle.svm.core.util.TimeUtils;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedThread;

public class TestOldObjectVirtualThreadSampleEvent extends JfrOldObjectTest {
    @Test
    public void test() throws Throwable {
        int arrayLength = Integer.MIN_VALUE;
        TinyObject obj = new TinyObject(44);
        AtomicLong sampledThreadId = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Recording recording = startRecording();
        Thread thread = Thread.ofVirtual().start(() -> {
            sampledThreadId.set(Thread.currentThread().threadId());
            sampleInVirtualThread(obj, arrayLength, failure);
        });
        thread.join();

        assertTrue(sampledThreadId.get() > 0);
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw throwable;
        }

        stopRecording(recording, events -> validateVirtualThreadEvents(events, TinyObject.class, arrayLength, sampledThreadId.get()));
    }

    private void validateVirtualThreadEvents(List<RecordedEvent> events, Class<?> expectedSampledType, int expectedArrayLength, long expectedThreadId) {
        assertFalse(events.isEmpty());

        int matchingEvents = 0;
        String expectedTypeName = expectedSampledType.getName();
        for (RecordedEvent event : events) {
            RecordedObject object = event.getValue("object");
            assertNotNull(object);

            if (object.getClass("type").getName().equals(expectedTypeName)) {
                RecordedThread eventThread = event.getValue("eventThread");
                assertNotNull("No event thread", eventThread);
                assertEquals(expectedThreadId, eventThread.getJavaThreadId());

                List<RecordedFrame> frames = event.getStackTrace().getFrames();
                assertFalse(frames.isEmpty());
                assertTrue(frames.stream().anyMatch(e -> "sampleInVirtualThread".equals(e.getMethod().getName())));
                checkTopStackFrame(event, "sampleInVirtualThread");

                assertEquals(0, event.getDuration().toMillis());
                assertTrue(event.getLong("startTime") > 0);
                assertTrue(event.getLong("allocationTime") > 0);
                assertTrue(event.getLong("objectSize") > 0);
                assertTrue(event.getLong("objectAge") > 0);
                assertTrue(event.getLong("lastKnownHeapUsage") > 0);
                assertEquals(expectedArrayLength, event.getInt("arrayElements"));
                assertNull(event.getValue("root"));
                matchingEvents++;
            }
        }

        assertTrue(matchingEvents > 0);
    }

    @SuppressWarnings("unused")
    private static void sampleInVirtualThread(TinyObject obj, int arrayLength, AtomicReference<Throwable> failure) {
        boolean success;
        long endTime = System.currentTimeMillis() + TimeUtils.secondsToMillis(5);
        do {
            success = SubstrateJVM.getOldObjectProfiler().sample(obj, Word.unsigned(1024 * 1024 * 1024), arrayLength);
        } while (!success && System.currentTimeMillis() < endTime);

        if (!success) {
            failure.set(new AssertionError("Timed out waiting for sampling to complete"));
        }
    }
}
