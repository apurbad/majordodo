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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;

public class TasksHeapBenchTest {

    private static final int TASKTYPE_MYTASK1 = 1234;
    private static final int TASKTYPE_MYTASK2 = 1235;
    private static final String USERID1 = "myuser1";
    private static final String USERID2 = "myuser2";
    private static final int GROUPID1 = 9713;
    private static final int GROUPID2 = 972;

    private GroupMapperFunction DEFAULT_FUNCTION = new GroupMapperFunction() {

        @Override
        public int getGroup(long taskid, int tasktype, String assignerData) {            
            switch (assignerData) {
                case USERID1:
                    return GROUPID1;
                case USERID2:
                    return GROUPID2;
                default:
                    return -1;
            }
        }
    };

    @Test
    public void monoThreadTests() throws Exception {
        TasksHeap instance = new TasksHeap(1000000, DEFAULT_FUNCTION);
        instance.setMaxFragmentation(Integer.MAX_VALUE);

        {
            long _start = System.currentTimeMillis();
            for (int i = 0; i < 1000000; i++) {
                instance.insertTask(i+1, TASKTYPE_MYTASK1, USERID1);
            }
            long _stop = System.currentTimeMillis();
            System.out.println("Time: " + (_stop - _start) + " ms");
        }

//        instance.scan(entry -> {
//            System.out.println("entry:" + entry);
//        });
//        System.out.println("after:");

        {
            long _start = System.currentTimeMillis();
            Map<Integer, Integer> availableSpace = new HashMap<>();
            availableSpace.put(TASKTYPE_MYTASK1, 1);
            for (int i = 0; i < 1000; i++) {
                instance.takeTasks(1, Arrays.asList(Task.GROUP_ANY), availableSpace);
//                instance.scan(entry -> {
//                    System.out.println("entry:" + entry);
//                });
            }

            long _stop = System.currentTimeMillis();
            System.out.println("Time: " + (_stop - _start) + " ms");
        }

    }

}