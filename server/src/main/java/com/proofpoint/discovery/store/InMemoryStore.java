/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.discovery.store;

import com.google.common.primitives.Longs;
import com.proofpoint.discovery.DiscoveryConfig;

import javax.inject.Inject;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkNotNull;

public class InMemoryStore
        implements LocalStore
{
    private final ConcurrentMap<ByteBuffer, Entry> map = new ConcurrentHashMap<>();
    private final long maxAgeInMs;
    private UpdateListener updateListener = null;

    @Inject
    public InMemoryStore(DiscoveryConfig config)
    {
        maxAgeInMs = config.getMaxAge().toMillis();
    }

    InMemoryStore()
    {
        maxAgeInMs = Long.MAX_VALUE;
    }

    public void setUpdateListener(UpdateListener updateListener)
    {
        this.updateListener = updateListener;
    }

    @Override
    public boolean put(Entry entry)
    {
        if (maxAgeInMs != Long.MAX_VALUE && entry.getMaxAgeInMs() == null) {
            entry = new Entry(entry.getKey(),
                    entry.getValue(),
                    entry.getTimestamp(),
                    maxAgeInMs);
        }

        ByteBuffer key = ByteBuffer.wrap(entry.getKey());

        while (true) {
            Entry old = map.putIfAbsent(key, entry);
            if (old == null) {
                return true;
            }

            entry = resolve(old, entry);
            if (entry == old) {
                return false;
            }

            if (map.replace(key, old, entry)) {
                if (updateListener != null) {
                    updateListener.notifyUpdate(old, entry);
                }
                return true;
            }
        }
    }

    @Override
    public Entry get(byte[] key)
    {
        checkNotNull(key, "key is null");

        return map.get(ByteBuffer.wrap(key));
    }

    @Override
    public boolean delete(byte[] key, long timestamp)
    {
        checkNotNull(key, "key is null");

        ByteBuffer wrappedKey = ByteBuffer.wrap(key);

        boolean done = false;
        while (!done) {
            Entry old = map.get(wrappedKey);

            if (old == null || isNewer(old, timestamp)) {
                return false;
            }
            else {
                done = map.remove(wrappedKey, old);
            }
        }
        return true;
    }

    @Override
    public Iterable<Entry> getAll()
    {
        return map.values();
    }

    private static Entry resolve(Entry a, Entry b)
    {
        if (isNewer(b, a.getTimestamp())) {
            return b;
        }
        else {
            return a;
        }
    }

    private static boolean isNewer(Entry entry, long timestamp) {
        return (Longs.compare(entry.getTimestamp(), timestamp) > 0);
    }
}
