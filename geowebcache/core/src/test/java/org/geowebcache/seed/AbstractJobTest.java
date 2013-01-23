package org.geowebcache.seed;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.classextension.EasyMock.*;

import java.util.ArrayList;
import java.util.Collection;

import org.easymock.Capture;
import org.easymock.IAnswer;
import org.easymock.IExpectationSetters;
import org.geowebcache.seed.GWCTask.STATE;
import org.geowebcache.seed.Job;
import org.geowebcache.storage.TileRangeIterator;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

/**
 * Tests for common behaviour of classes implementing the Job interface.
 *
 */
public abstract class AbstractJobTest {

/**
 * Expect doActionInternal to be called on a mock GWCTask.
 * @param mockTask
 * @return
 * @throws Throwable
 */
public static IExpectationSetters<Object> expectDoActionInternal(GWCTask mockTask) throws Exception {
    mockTask.doActionInternal();
    return expectLastCall();
}
/**
 * Expect dispose to be called on a mock GWCTask.
 * @param mockTask
 * @return
 * @throws Throwable
 */
public static IExpectationSetters<Object> expectDispose(GWCTask mockTask) throws Exception {
    mockTask.dispose();
    return expectLastCall();
}

/**
 * Return a Job with a single EasyMocked task and initialised with the provided TileRangeIterator
 * @param tri
 * @return
 */
protected abstract Job initNextLocation(TileRangeIterator tri) throws Exception;

/**
 * Assert that a TileRequest is at the specified grid location
 * @param tr TileRequest to compare
 * @param gridLoc expected grid location {x,y,zoom}
 */
public static void assertTileRequestAt(TileRequest tr, long[] gridLoc) throws Exception {
    assertTrue(String.format("Expected: TileRequest at <%d, %d, %d>, Result: TileRequest at <%d, %d, %d>", gridLoc[0], gridLoc[1], gridLoc[2], tr.getX(), tr.getY(), tr.getZoom()),
            tr.getX()==gridLoc[0] && tr.getY()==gridLoc[1] && tr.getZoom()==gridLoc[2]);
}

/**
 * Test that getNextRequest behaves as expected
 * @throws Exception
 */
@Test
public void testGetNextRequest() throws Exception {
    TileRangeIterator tri = createMock(TileRangeIterator.class);
    
    expect(tri.nextMetaGridLocation()).andReturn(new long[] {1,2,3});
    expect(tri.nextMetaGridLocation()).andReturn(new long[] {4,5,6});
    expect(tri.nextMetaGridLocation()).andReturn(null).anyTimes();
    replay(tri);
    
    Job job = initNextLocation(tri);
    
    TileRequest tr;
    tr = job.getNextLocation();
    assertTileRequestAt(tr, new long[] {1,2,3});
    tr = job.getNextLocation();
    assertTileRequestAt(tr, new long[] {4,5,6});
    tr = job.getNextLocation();
    assertNull(tr);
    tr = job.getNextLocation();
    assertNull(tr);
}

/**
 * Return a Job with EasyMock GWCTasks that report the given states when their getState methods are called.
 * @param states
 * @return
 */
protected abstract Job jobWithTaskStates(STATE... states) throws Exception;

/**
 * Assert that a job with tasks in the given states has the expected state.
 * @param expected The state expected of the job
 * @param states The states of the tasks in the job
 */
protected void assertGetState(STATE expected, STATE... states) throws Exception{
    Job job = jobWithTaskStates(states);
    assertEquals(expected, job.getState());
}

/**
 * Test that getState gives correct results for a single task.
 * @throws Exception
 */
@Test
public void testGetStateSingle() throws Exception {
    
    assertGetState(STATE.READY, 
            STATE.READY);

    assertGetState(STATE.DEAD, 
            STATE.DEAD);

    assertGetState(STATE.RUNNING, 
            STATE.RUNNING);

    assertGetState(STATE.READY, 
            STATE.UNSET);

    assertGetState(STATE.DONE, 
            STATE.DONE);
}

/**
 * Test that getState returns DONE when expected.
 * @throws Exception
 */
@Test
public void testGetStateDone() throws Exception {
    assertGetState(STATE.DONE, 
            STATE.DONE, STATE.DONE);

    assertGetState(STATE.DONE, 
            STATE.DONE, STATE.READY);

    assertGetState(STATE.DONE, 
            STATE.READY, STATE.DONE);

    assertGetState(STATE.DONE, 
            STATE.DONE, STATE.UNSET);

    assertGetState(STATE.DONE, 
            STATE.UNSET, STATE.DONE);
    
}

/**
 * Test that getState returns READY when expected
 * @throws Exception
 */
@Test
public void testGetStateReady() throws Exception {
    assertGetState(STATE.READY, 
            STATE.READY, STATE.READY);

    assertGetState(STATE.READY, 
            STATE.UNSET, STATE.UNSET);

    assertGetState(STATE.READY, 
            STATE.READY, STATE.UNSET);

    assertGetState(STATE.READY, 
            STATE.UNSET, STATE.READY);
    
}

/**
 * Test that getState returns RUNNING when expected
 * @throws Exception
 */
@Test
public void testGetStateRunning() throws Exception {
    assertGetState(STATE.RUNNING, 
            STATE.RUNNING, STATE.RUNNING);

    assertGetState(STATE.RUNNING, 
            STATE.RUNNING, STATE.DONE);
    assertGetState(STATE.RUNNING, 
            STATE.RUNNING, STATE.UNSET);
    assertGetState(STATE.RUNNING, 
            STATE.RUNNING, STATE.READY);
    
}

/**
 * Test that getState returns DEAD when expected
 * @throws Exception
 */
@Test
public void testGetStateDead() throws Exception {
    assertGetState(STATE.DEAD, 
            STATE.DEAD, STATE.DEAD);

    assertGetState(STATE.DEAD, 
            STATE.DEAD, STATE.RUNNING);
    assertGetState(STATE.DEAD, 
            STATE.RUNNING, STATE.DEAD);
    
    assertGetState(STATE.DEAD, 
            STATE.DEAD, STATE.DONE);
    assertGetState(STATE.DEAD, 
            STATE.DONE, STATE.DEAD);
    
    assertGetState(STATE.DEAD, 
            STATE.DEAD, STATE.READY);
    assertGetState(STATE.DEAD, 
            STATE.READY, STATE.DEAD);
    
    assertGetState(STATE.DEAD, 
            STATE.DEAD, STATE.UNSET);
    assertGetState(STATE.DEAD, 
            STATE.UNSET, STATE.DEAD);
    
}

/**
 * Test that getState returns UNSET when expected
 * @throws Exception
 */
@Test
public void testGetStateUnset() throws Exception {
    // This is never expected
}

/**
 * 
 * @throws Exception
 */
@Test
public void testGetStatus() throws Exception {
    TileBreeder mockBreeder = createMockTileBreeder();
    SeedTask task1 = SeedTestUtils.createMockSeedTask(mockBreeder);
    Collection<TaskStatus> taskStatusCol = new ArrayList<TaskStatus>();
    taskStatusCol.add(createMock(TaskStatus.class));
    expect(task1.getStatus()).andReturn(taskStatusCol.iterator().next());
    replay(task1);
    replay(mockBreeder);
    
    Job job = createTestSeedJob(mockBreeder, 1);
    
    JobStatus status = job.getStatus();
    assertTrue("JobStatus timestamp is more than 100 ms from expected time.",Math.abs(System.currentTimeMillis()-status.getTime())<100);
    
    assertThat(status.getTaskStatuses(), containsInAnyOrder(taskStatusCol.toArray()));
}

/**
 * Test the terminate method
 * @throws Exception
 */
@Test
public void testTerminate() throws Exception {
    Job job = jobWithTaskStates(STATE.DONE, STATE.RUNNING, STATE.RUNNING, STATE.UNSET, STATE.READY);
    
    for(GWCTask task: job.getTasks()){
        STATE s = task.getState();
        reset(task);
        
        expect(task.getState()).andReturn(s).anyTimes();
        if(s != STATE.DEAD && s != STATE.DONE){
            task.terminateNicely();
            expectLastCall().once();
        }
        replay(task);
    }
    
    job.terminate();
    
    for(GWCTask task: job.getTasks()){
        verify(task);
    }
}
/**
 * Test the terminate method with single tasks
 * @throws Exception
 */
@Test
public void testTerminateSingle() throws Exception {
    
    STATE[] states = {STATE.DONE, STATE.RUNNING, STATE.RUNNING, STATE.UNSET, STATE.READY};
    
    for(STATE s: states){
        Job job = jobWithTaskStates(s);
        GWCTask task = job.getTasks()[0];
        reset(task);
        
        expect(task.getState()).andReturn(s).anyTimes();
        if(s != STATE.DEAD && s != STATE.DONE){
            task.terminateNicely();
            expectLastCall().once();
        }
        replay(task);
        job.terminate();
        verify(task);
    }
}

/**
 * Returns a mock of an appropriate concrete subclass of TileBreeder in the record phase.
 */
protected abstract TileBreeder createMockTileBreeder();

/**
 * 
 * @param breeder
 * @param threads
 * @return
 */
protected abstract Job createTestSeedJob(TileBreeder breeder, int threads);


}