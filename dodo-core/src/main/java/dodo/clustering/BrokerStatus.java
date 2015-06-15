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

import dodo.scheduler.WorkerStatus;
import dodo.client.TaskStatusView;
import dodo.client.WorkerStatusView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongUnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replicated status of the broker. Each broker, leader or follower, contains a
 * copy of this status. The status is replicated to follower by reading the
 * StatusChangesLog
 *
 * @author enrico.olivelli
 */
public class BrokerStatus {

    private static final Logger LOGGER = Logger.getLogger(BrokerStatus.class.getName());

    private final Map<Long, Task> tasks = new HashMap<>();
    private final AtomicLong nextTaskId = new AtomicLong(0);
    private final Map<String, WorkerStatus> workers = new HashMap<>();
    private final AtomicLong newTaskId = new AtomicLong();
    private long maxTaskId = -1;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final StatusChangesLog log;

    public WorkerStatus getWorkerStatus(String workerId) {
        return workers.get(workerId);
    }

    public BrokerStatus(StatusChangesLog log) {
        this.log = log;
    }

    public List<WorkerStatusView> getAllWorkers() {
        List<WorkerStatusView> result = new ArrayList<>();
        lock.readLock().lock();
        try {
            workers.values().stream().forEach((k) -> {
                result.add(createWorkerStatusView(k));
            });
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    public List<TaskStatusView> getAllTasks() {
        List<TaskStatusView> result = new ArrayList<>();
        lock.readLock().lock();
        try {
            tasks.values().stream().forEach((k) -> {
                result.add(createTaskStatusView(k));
            });
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    private TaskStatusView createTaskStatusView(Task task) {
        if (task == null) {
            return null;
        }
        TaskStatusView s = new TaskStatusView();
        s.setCreatedTimestamp(task.getCreatedTimestamp());
        s.setUserid(task.getUserId());
        s.setWorkerId(task.getWorkerId());
        s.setStatus(task.getStatus());
        s.setTaskId(task.getTaskId());
        s.setParameter(task.getParameter());
        s.setType(task.getType());
        s.setResult(task.getResult()); // should be cloned

        return s;
    }

    private WorkerStatusView createWorkerStatusView(WorkerStatus k) {
        WorkerStatusView res = new WorkerStatusView();
        res.setId(k.getWorkerId());
        res.setLocation(k.getWorkerLocation());
        res.setLastConnectionTs(k.getLastConnectionTs());
        String s;
        switch (k.getStatus()) {
            case WorkerStatus.STATUS_CONNECTED:
                s = "CONNECTED";
                break;
            case WorkerStatus.STATUS_DEAD:
                s = "DEAD";
                break;
            case WorkerStatus.STATUS_DISCONNECTED:
                s = "DISCONNECTED";
                break;
            default:
                s = "?" + k.getStatus();
        }
        res.setProcessId(k.getProcessId());
        res.setStatus(s);
        return res;
    }

    public Collection<WorkerStatus> getWorkersAtBoot() {
        return workers.values();
    }

    public Collection<Task> getTasksAtBoot() {
        return tasks.values();
    }

    public long nextTaskId() {
        return nextTaskId.incrementAndGet();
    }

    public static final class ModificationResult {

        public final LogSequenceNumber sequenceNumber;
        public final long newTaskId;

        public ModificationResult(LogSequenceNumber sequenceNumber, long newTaskId) {
            this.sequenceNumber = sequenceNumber;
            this.newTaskId = newTaskId;
        }

    }

    public ModificationResult applyModification(StatusEdit edit) throws LogNotAvailableException {
        LogSequenceNumber num = log.logStatusEdit(edit); // ? out of the lock ?
        return applyEdit(num, edit);
    }

    /**
     * Apply the modification to the status, this operation cannot fail, a
     * failure MUST lead to the death of the JVM because it will not be
     * recoverable as the broker will go out of synch
     *
     * @param edit
     */
    private ModificationResult applyEdit(LogSequenceNumber num, StatusEdit edit) {
        LOGGER.log(Level.FINE, "applyEdit {0}", edit);

        lock.writeLock().lock();
        try {
            switch (edit.editType) {
                case StatusEdit.TYPE_ASSIGN_TASK_TO_WORKER: {
                    long taskId = edit.taskId;
                    String workerId = edit.workerId;
                    Task task = tasks.get(taskId);
                    task.setStatus(Task.STATUS_RUNNING);
                    task.setWorkerId(workerId);
                    return new ModificationResult(num, -1);
                }
                case StatusEdit.TYPE_TASK_FINISHED: {
                    long taskId = edit.taskId;
                    String workerId = edit.workerId;
                    Task task = tasks.get(taskId);
                    if (task == null) {
                        throw new IllegalStateException();
                    }
                    if (!task.getWorkerId().equals(workerId)) {
                        throw new IllegalStateException("task " + taskId + ", bad workerid " + workerId + ", expected " + task.getWorkerId());
                    }
                    task.setStatus(Task.STATUS_FINISHED);
                    task.setResult(edit.result);
                    return new ModificationResult(num, -1);
                }
                case StatusEdit.ACTION_TYPE_ADD_TASK: {
                    Task task = new Task();
                    task.setTaskId(edit.taskId);
                    if (maxTaskId < edit.taskId) {
                        maxTaskId = edit.taskId;
                    }
                    task.setCreatedTimestamp(System.currentTimeMillis());
                    task.setParameter(edit.parameter);
                    task.setType(edit.taskType);
                    task.setUserId(edit.userid);
                    task.setStatus(Task.STATUS_WAITING);
                    tasks.put(edit.taskId, task);
                    return new ModificationResult(num, edit.taskId);
                }
                case StatusEdit.ACTION_TYPE_WORKER_CONNECTED: {
                    WorkerStatus node = workers.get(edit.workerId);
                    if (node == null) {
                        node = new WorkerStatus();
                        node.setWorkerId(edit.workerId);

                        workers.put(edit.workerId, node);
                    }
                    node.setStatus(WorkerStatus.STATUS_CONNECTED);
                    node.setWorkerLocation(edit.workerLocation);
                    node.setProcessId(edit.workerProcessId);
                    node.setLastConnectionTs(edit.timestamp);
                    return new ModificationResult(num, -1);
                }
                default:
                    throw new IllegalArgumentException();

            }
        } finally {
            lock.writeLock().unlock();
        }

    }

    public void recover() {

        lock.writeLock().lock();
        try {
            // TODO: maxTaskId must be saved on snapshots, because tasks will be removed and we do not want to reuse taskids
            BrokerStatusSnapshot snapshot = log.loadBrokerStatusSnapshot();
            log.recovery(snapshot.getActualLogSequenceNumber(),
                    (logSeqNumber, edit) -> {
                        applyEdit(logSeqNumber, edit);
                    });
            newTaskId.set(maxTaskId + 1);
        } catch (LogNotAvailableException err) {
            throw new RuntimeException(err);
        } finally {
            lock.writeLock().unlock();
        }

    }

    public Task getTask(long taskId) {
        lock.readLock().lock();
        try {
            return tasks.get(taskId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public TaskStatusView getTaskStatus(long taskId) {
        Task task;
        lock.readLock().lock();
        try {
            task = tasks.get(taskId);
        } finally {
            lock.readLock().unlock();
        }

        TaskStatusView s = createTaskStatusView(task);
        return s;
    }

}
