/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.agent.support;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SupportBudget {
    private final AtomicInteger remaining = new AtomicInteger(12);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    public boolean acquire() { return !cancelled.get() && System.nanoTime() < deadline && remaining.getAndDecrement() > 0; }
    public boolean cancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); }
}
