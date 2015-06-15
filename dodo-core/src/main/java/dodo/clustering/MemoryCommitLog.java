/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package dodo.clustering;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * In memory commit log
 *
 * @author enrico.olivelli
 */
public class MemoryCommitLog extends StatusChangesLog {

    private long sequenceNumber = 0;
    private final List<MemoryLogLine> log = new ArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public LogSequenceNumber logStatusEdit(StatusEdit action) {
        lock.lock();
        try {
            long newNumber = ++sequenceNumber;
            LogSequenceNumber snum = new LogSequenceNumber(1, newNumber);
            log.add(new MemoryLogLine(snum, action));
            return snum;
        } finally {
            lock.unlock();
        }
    }

    private List<MemoryLogLine> logatboot;
    private BrokerStatusSnapshot snapshotatboot = new BrokerStatusSnapshot(0, new LogSequenceNumber(0, 0));

    public MemoryCommitLog() {
    }

    public MemoryCommitLog(List<MemoryLogLine> logatboot, BrokerStatusSnapshot snapshotatboot) {
        this.logatboot = logatboot;
        this.snapshotatboot = snapshotatboot;
    }

    public static final class MemoryLogLine {

        private final LogSequenceNumber logSequenceNumber;
        private final StatusEdit edit;

        public MemoryLogLine(LogSequenceNumber logSequenceNumber, StatusEdit edit) {
            this.logSequenceNumber = logSequenceNumber;
            this.edit = edit;
        }

    }

    @Override
    public void recovery(LogSequenceNumber snapshotSequenceNumber, BiConsumer<LogSequenceNumber, StatusEdit> consumer) throws LogNotAvailableException {
        if (logatboot != null) {
            for (MemoryLogLine line : logatboot) {
                if (line.logSequenceNumber.after(snapshotSequenceNumber)) {
                    consumer.accept(line.logSequenceNumber, line.edit);
                }
            };
        }
        logatboot = null;
    }

    @Override
    public void checkpointDone(BrokerStatusSnapshot snapshotData) throws LogNotAvailableException {
        lock.lock();
        try {
            for (Iterator<MemoryLogLine> it = log.iterator(); it.hasNext();) {
                MemoryLogLine line = it.next();
                if (snapshotData.actualLogSequenceNumber.after(line.logSequenceNumber)) {
                    it.remove();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public BrokerStatusSnapshot loadBrokerStatusSnapshot() throws LogNotAvailableException {
        return snapshotatboot;
    }

}
