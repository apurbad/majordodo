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
package majordodo.replication;

import majordodo.task.BrokerStatusSnapshot;
import majordodo.task.LogNotAvailableException;
import majordodo.task.LogSequenceNumber;
import majordodo.task.StatusChangesLog;
import majordodo.task.StatusEdit;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * Commit log replicated on Apache Bookeeper
 *
 * @author enrico.olivelli
 */
public class ReplicatedCommitLog extends StatusChangesLog {

    private static final Logger LOGGER = Logger.getLogger(ReplicatedCommitLog.class.getName());

    private static final byte[] magic = "dodo".getBytes(StandardCharsets.UTF_8);
    private BookKeeper bookKeeper;
    private ZKClusterManager zKClusterManager;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock snapshotLock = new ReentrantLock();
    private CommitFileWriter writer;
    private long currentLedgerId = 0;
    private Path snapshotsDirectory;
    private List<Long> actualLedgersList;

    private class CommitFileWriter implements AutoCloseable {

        LedgerHandle out;

        private CommitFileWriter() throws LogNotAvailableException {
            try {
                this.out = bookKeeper.createLedger(1, 1, 1, BookKeeper.DigestType.MAC, magic);
            } catch (Exception err) {
                throw new LogNotAvailableException(err);
            }
        }

        public long getLedgerId() {
            return this.out.getId();
        }

        public long writeEntry(StatusEdit edit) throws LogNotAvailableException {
            try {
                byte[] serialize = edit.serialize();
                return this.out.addEntry(serialize);
            } catch (Exception err) {
                LOGGER.log(Level.SEVERE, "error while writing to ledger", err);
                throw new LogNotAvailableException(err);
            }
        }

        public void close() throws LogNotAvailableException {
            try {
                out.close();
            } catch (Exception err) {
                throw new LogNotAvailableException(err);
            }
        }
    }

    private final LeaderShipChangeListener leaderShiplistener = new LeaderShipChangeListener() {

        @Override
        public void leadershipLost() {
            LOGGER.log(Level.SEVERE, "leadershipLost");
        }

        @Override
        public void leadershipAcquired() {
            LOGGER.log(Level.SEVERE, "leadershipAcquired");
        }

    };

    public ReplicatedCommitLog(String zkAddress, int zkTimeout, String zkPath, Path snapshotsDirectory, byte[] localhostdata) throws Exception {
        if (localhostdata == null) {
            localhostdata = new byte[0];
        }
        ClientConfiguration config = new ClientConfiguration();
        try {
            this.zKClusterManager = new ZKClusterManager(zkAddress, zkTimeout, zkPath, leaderShiplistener, localhostdata);
            this.zKClusterManager.waitForConnection();
            this.bookKeeper = new BookKeeper(config, zKClusterManager.getZooKeeper());
            this.snapshotsDirectory = snapshotsDirectory;
            this.zKClusterManager.start();
        } catch (Exception t) {
            close();
            throw t;
        }
    }

    @Override
    public LogSequenceNumber logStatusEdit(StatusEdit edit) throws LogNotAvailableException {
        writeLock.lock();
        try {
            if (writer == null) {
                throw new LogNotAvailableException(new Exception("no ledger opened for writing"));
            }
            long newSequenceNumber = writer.writeEntry(edit);
            return new LogSequenceNumber(currentLedgerId, newSequenceNumber);
        } finally {
            writeLock.unlock();
        }
    }

    private void openNewLedger() throws LogNotAvailableException {
        writeLock.lock();
        try {
            writer = new CommitFileWriter();
            currentLedgerId = writer.getLedgerId();
            LOGGER.log(Level.SEVERE, "Opened new ledger:" + currentLedgerId);
            actualLedgersList.add(currentLedgerId);
            zKClusterManager.saveActualLedgersList(actualLedgersList);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void recovery(LogSequenceNumber snapshotSequenceNumber, BiConsumer<LogSequenceNumber, StatusEdit> consumer) throws LogNotAvailableException {
        this.actualLedgersList = zKClusterManager.getActualLedgersList();
        LOGGER.log(Level.SEVERE, "Actual ledgers list:" + actualLedgersList);
        LOGGER.log(Level.SEVERE, "Latest snapshot ledgerId:" + currentLedgerId);
        if (currentLedgerId > 0 && !this.actualLedgersList.contains(currentLedgerId)) {
            // TODO: download snapshot from another remote broker
            throw new LogNotAvailableException(new Exception("Actual ledgers list does not include latest snapshot ledgerid:" + currentLedgerId + ". manual recoveryis needed (pickup a recent snapshot from a live broker please)"));
        }
        try {
            for (long ledgerId : actualLedgersList) {
                LOGGER.log(Level.SEVERE, "Recovering from ledger " + ledgerId);
                LedgerHandle handle = bookKeeper.openLedgerNoRecovery(ledgerId, BookKeeper.DigestType.MAC, magic);
                try {
                    long lastAddConfirmed = handle.getLastAddConfirmed();
                    LOGGER.log(Level.SEVERE, "Recovering from ledger " + ledgerId + ", lastAddConfirmed=" + lastAddConfirmed);
                    if (lastAddConfirmed >= 0) {
                        for (Enumeration<LedgerEntry> en = handle.readEntries(0, lastAddConfirmed); en.hasMoreElements();) {
                            LedgerEntry entry = en.nextElement();

                            LogSequenceNumber number = new LogSequenceNumber(ledgerId, entry.getEntryId());
                            StatusEdit statusEdit = StatusEdit.read(entry.getEntry());
                            if (number.after(snapshotSequenceNumber)) {
                                LOGGER.log(Level.INFO, "RECOVER ENTRY {0}, {1}", new Object[]{number, statusEdit});
                                consumer.accept(number, statusEdit);
                            } else {
                                LOGGER.log(Level.INFO, "SKIP ENTRY {0}, {1}", new Object[]{number, statusEdit});
                            }
                        }
                    }
                } finally {
                    handle.close();
                }
            }
        } catch (Exception err) {
            throw new LogNotAvailableException(err);
        }
    }

    @Override
    public void startWriting() throws LogNotAvailableException {
        openNewLedger();
    }

    @Override
    public boolean isWritable() {
        return writer != null;
    }

    @Override
    public void checkpoint(BrokerStatusSnapshot snapshotData) throws LogNotAvailableException {
        snapshotLock.lock();
        try {
            LogSequenceNumber actualLogSequenceNumber = snapshotData.getActualLogSequenceNumber();
            String filename = actualLogSequenceNumber.ledgerId + "_" + actualLogSequenceNumber.sequenceNumber;
            Path snapshotfilename = snapshotsDirectory.resolve(filename + SNAPSHOTFILEXTENSION);
            LOGGER.log(Level.INFO, "checkpoint, file:{0}", snapshotfilename.toAbsolutePath());
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> filedata = BrokerStatusSnapshot.serializeSnaphsot(actualLogSequenceNumber, snapshotData);

            try (OutputStream out = Files.newOutputStream(snapshotfilename)) {
                mapper.writeValue(out, filedata);
            } catch (IOException err) {
                throw new LogNotAvailableException(err);
            }
        } finally {
            snapshotLock.unlock();
        }
    }

    private static final String SNAPSHOTFILEXTENSION = ".snap.json";

    @Override
    public BrokerStatusSnapshot loadBrokerStatusSnapshot() throws LogNotAvailableException {
        Path snapshotfilename = null;
        LogSequenceNumber latest = null;

        try (DirectoryStream<Path> allfiles = Files.newDirectoryStream(snapshotsDirectory)) {
            for (Path path : allfiles) {
                String filename = path.getFileName().toString();
                if (filename.endsWith(SNAPSHOTFILEXTENSION)) {
                    System.out.println("Processing snapshot file: " + path);
                    try {
                        filename = filename.substring(0, filename.length() - SNAPSHOTFILEXTENSION.length());

                        int pos = filename.indexOf('_');
                        if (pos > 0) {
                            long ledgerId = Long.parseLong(filename.substring(0, pos));
                            long sequenceNumber = Long.parseLong(filename.substring(pos + 1));
                            LogSequenceNumber number = new LogSequenceNumber(ledgerId, sequenceNumber);
                            if (latest == null || number.after(latest)) {
                                latest = number;
                                snapshotfilename = path;
                            }
                        }
                    } catch (NumberFormatException invalidName) {
                        System.out.println("Error:" + invalidName);
                        invalidName.printStackTrace();
                    }
                }
            }
        } catch (IOException err) {
            throw new LogNotAvailableException(err);
        }
        if (snapshotfilename == null) {
            System.out.println("No snapshot available Starting with a brand new status");
            currentLedgerId = -1;
            return new BrokerStatusSnapshot(0, 0, new LogSequenceNumber(-1, -1));
        } else {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> snapshotdata;
            try (InputStream in = Files.newInputStream(snapshotfilename)) {
                snapshotdata = mapper.readValue(in, Map.class);
                BrokerStatusSnapshot result = BrokerStatusSnapshot.deserializeSnapshot(snapshotdata);
                currentLedgerId = result.getActualLogSequenceNumber().ledgerId;
                return result;
            } catch (IOException err) {
                throw new LogNotAvailableException(err);
            }
        }
    }

    private volatile boolean closed = false;

    @Override
    public final void close() {
        LOGGER.severe("closing");
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception err) {
                err.printStackTrace();
            } finally {
                writer = null;
            }
        }
        if (zKClusterManager != null) {
            try {
                zKClusterManager.close();
            } finally {
                zKClusterManager = null;
            }
        }
        closed = true;
        LOGGER.severe("closed");
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void requestLeadership() throws LogNotAvailableException {
        zKClusterManager.requestLeadership();
    }

    @Override
    public void followTheLeader(LogSequenceNumber skipPast, BiConsumer<LogSequenceNumber, StatusEdit> consumer) throws LogNotAvailableException {

        List<Long> actualList = zKClusterManager.getActualLedgersList();

        List<Long> toRead = actualList;
        if (skipPast.ledgerId != -1) {
            toRead = toRead.stream().filter(l -> l >= skipPast.ledgerId).collect(Collectors.toList());
        }
        try {
            long nextEntry = skipPast.sequenceNumber + 1;
            //LOGGER.log(Level.SEVERE, "followTheLeader skipPast:" + skipPast + " toRead: " + toRead + " actualList:" + actualList + ", nextEntry:" + nextEntry);
            for (Long previous : toRead) {
                //LOGGER.log(Level.SEVERE, "followTheLeader openLedger " + previous + " nextEntry:" + nextEntry);
                LedgerHandle lh;
                try {
                    lh = bookKeeper.openLedgerNoRecovery(previous,
                            BookKeeper.DigestType.MAC, magic);
                } catch (BKException.BKLedgerRecoveryException e) {
                    LOGGER.log(Level.SEVERE, "error", e);
                    return;
                }
                long lastAddConfirmed = lh.getLastAddConfirmed();
                LOGGER.log(Level.SEVERE, "followTheLeader openLedger " + previous + " -> lastAddConfirmed:" + lastAddConfirmed);
                if (nextEntry > lastAddConfirmed) {
                    nextEntry = 0;
                    continue;
                }
                Enumeration<LedgerEntry> entries
                        = lh.readEntries(nextEntry, lh.getLastAddConfirmed());

                while (entries.hasMoreElements()) {
                    LedgerEntry e = entries.nextElement();
                    long entryId = e.getEntryId();

                    byte[] entryData = e.getEntry();
                    StatusEdit statusEdit = StatusEdit.read(entryData);
                    LOGGER.log(Level.SEVERE, "entry " + previous + "," + entryId + " -> " + statusEdit);
                    LogSequenceNumber number = new LogSequenceNumber(previous, entryId);
                    consumer.accept(number, statusEdit);

                }
            }
        } catch (InterruptedException | IOException | BKException err) {
            err.printStackTrace();
            throw new LogNotAvailableException(err);
        }
    }

    @Override
    public boolean isLeader() {
        return zKClusterManager != null && zKClusterManager.isLeader();
    }

}