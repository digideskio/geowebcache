/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Marius Suta / The Open Planning Project 2008 (original code from SeedRestlet)
 * @author Arne Kepp / The Open Planning Project 2009 (original code from SeedRestlet)
 * @author Gabriel Roldan / OpenGeo 2010  
 * @author Kevin Smith / OpenGeo 2012
 */
package org.geowebcache.seed.threaded;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.TileLayerDispatcher;
import org.geowebcache.seed.GWCTask;
import org.geowebcache.seed.Job;
import org.geowebcache.seed.JobStatus;
import org.geowebcache.seed.SeedJob;
import org.geowebcache.seed.SeedRequest;
import org.geowebcache.seed.SeedTask;
import org.geowebcache.seed.SeederThreadPoolExecutor;
import org.geowebcache.seed.TaskStatus;
import org.geowebcache.seed.TileBreeder;
import org.geowebcache.seed.GWCTask.STATE;
import org.geowebcache.seed.GWCTask.TYPE;
import org.geowebcache.seed.TruncateJob;
import org.geowebcache.seed.TruncateTask;
import org.geowebcache.storage.StorageBroker;
import org.geowebcache.storage.TileRange;
import org.geowebcache.storage.TileRangeIterator;
import org.geowebcache.util.GWCVars;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.Assert;

/**
 * Class in charge of dispatching seed/truncate tasks.
 * <p>
 * As of version 1.2.4a+, it is possible to control how GWC behaves in the event that a backend (WMS
 * for example) request fails during seeding, using the following environment variables:
 * <ul>
 * <li>{@code GWC_SEED_RETRY_COUNT}: specifies how many times to retry a failed request for each
 * tile being seeded. Use {@code 0} for no retries, or any higher number. Defaults to {@code 0}
 * retry meaning no retries are performed. It also means that the defaults to the other two
 * variables do not apply at least you specify a higher value for GWC_SEED_RETRY_COUNT;
 * <li>{@code GWC_SEED_RETRY_WAIT}: specifies how much to wait before each retry upon a failure to
 * seed a tile, in milliseconds. Defaults to {@code 100ms};
 * <li>{@code GWC_SEED_ABORT_LIMIT}: specifies the aggregated number of failures that a group of
 * seeding threads should reach before aborting the seeding operation as a whole. This value is
 * shared by all the threads launched as a single thread group; so if the value is {@code 10} and
 * you launch a seed task with four threads, when {@code 10} failures are reached by all or any of
 * those four threads the four threads will abort the seeding task. The default is {@code 1000}.
 * </ul>
 * These environment variables can be established by any of the following ways, in order of
 * precedence:
 * <ol>
 * <li>As a Java environment variable: for example {@code java -DGWC_SEED_RETRY_COUNT=5 ...};
 * <li>As a Servlet context parameter: for example
 * 
 * <pre>
 * <code>
 *   &lt;context-param&gt;
 *    &lt;!-- milliseconds between each retry upon a backend request failure --&gt;
 *    &lt;param-name&gt;GWC_SEED_RETRY_WAIT&lt;/param-name&gt;
 *    &lt;param-value&gt;500&lt;/param-value&gt;
 *   &lt;/context-param&gt;
 * </code>
 * </pre>
 * 
 * In the web application's {@code WEB-INF/web.xml} configuration file;
 * <li>As a System environment variable:
 * {@code export GWC_SEED_ABORT_LIMIT=2000; <your usual command to run GWC here>}
 * </ol>
 * </p>
 * 
 * @author Gabriel Roldan, based on Marius Suta's and Arne Kepp's SeedRestlet
 */
public class ThreadedTileBreeder extends TileBreeder implements ApplicationContextAware {
    private static final String GWC_SEED_ABORT_LIMIT = "GWC_SEED_ABORT_LIMIT";

    private static final String GWC_SEED_RETRY_WAIT = "GWC_SEED_RETRY_WAIT";

    private static final String GWC_SEED_RETRY_COUNT = "GWC_SEED_RETRY_COUNT";

    private static Log log = LogFactory.getLog(ThreadedTileBreeder.class);

    private ThreadPoolExecutor threadPool;

    /**
     * How many retries per failed tile. 0 = don't retry, 1 = retry once if failed, etc
     */
    private int tileFailureRetryCount = 0;

    /**
     * How much (in milliseconds) to wait before trying again a failed tile
     */
    private long tileFailureRetryWaitTime = 100;

    /**
     * How many failures to tolerate before aborting the seed task. Value is shared between all the
     * threads of the same run.
     */
    private long totalFailuresBeforeAborting = 1000;

    private Map<Long, SubmittedTask> currentPool = new TreeMap<Long, SubmittedTask>();

    private AtomicLong currentTaskId = new AtomicLong();
    private AtomicLong currentJobId = new AtomicLong();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private static class SubmittedTask {
        public final GWCTask task;

        public final Future<GWCTask> future;

        public SubmittedTask(final GWCTask task, final Future<GWCTask> future) {
            this.task = task;
            this.future = future;
        }
    }

    private Collection<ThreadedJob> jobs;
    
    /**
     * Initializes the seed task failure control variables either with the provided environment
     * variable values or their defaults.
     * 
     * @see {@link ThreadedTileBreeder class' javadocs} for more information
     * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
     */
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        String retryCount = GWCVars.findEnvVar(applicationContext, GWC_SEED_RETRY_COUNT);
        String retryWait = GWCVars.findEnvVar(applicationContext, GWC_SEED_RETRY_WAIT);
        String abortLimit = GWCVars.findEnvVar(applicationContext, GWC_SEED_ABORT_LIMIT);

        tileFailureRetryCount = (int) toLong(GWC_SEED_RETRY_COUNT, retryCount, 0);
        tileFailureRetryWaitTime = toLong(GWC_SEED_RETRY_WAIT, retryWait, 100);
        totalFailuresBeforeAborting = toLong(GWC_SEED_ABORT_LIMIT, abortLimit, 1000);

        checkPositive(tileFailureRetryCount, GWC_SEED_RETRY_COUNT);
        checkPositive(tileFailureRetryWaitTime, GWC_SEED_RETRY_WAIT);
        checkPositive(totalFailuresBeforeAborting, GWC_SEED_ABORT_LIMIT);
    }

    @SuppressWarnings("serial")
    private void checkPositive(long value, String variable) {
        if (value < 0) {
            throw new BeanInitializationException(
                    "Invalid configuration value for environment variable " + variable
                            + ". It should be a positive integer.") {
            };
        }
    }

    private long toLong(String varName, String paramVal, long defaultVal) {
        if (paramVal == null) {
            return defaultVal;
        }
        try {
            return Long.valueOf(paramVal);
        } catch (NumberFormatException e) {
            log.warn("Invalid environment parameter for " + varName + ": '" + paramVal
                    + "'. Using default value: " + defaultVal);
        }
        return defaultVal;
    }

    /**
     * Create a job to manipulate the cache (Seed, truncate, etc).  In will still need to be dispatched.
     * 
     * @param tr The range of tiles to work on.
     * @param tl The layer to work on.  Overrides any layer specified on tr.
     * @param type The type of task(s) to create
     * @param threadCount The number of threads to use, forced to 1 if type is TRUNCATE
     * @param filterUpdate // TODO: What does this do?
     * @return Array of tasks.  Will have length threadCount or 1.
     * @throws GeoWebCacheException
     */
    public Job createJob(TileRange tr, TileLayer tl, GWCTask.TYPE type, int threadCount,
            boolean filterUpdate) throws GeoWebCacheException {

        if (type == GWCTask.TYPE.TRUNCATE || threadCount < 1) {
            log.trace("Forcing thread count to 1");
            threadCount = 1;
        }
        TileRangeIterator trIter = new TileRangeIterator(tr, tl.getMetaTilingFactors());
        ThreadedJob job;
        switch (type) {
        case TRUNCATE:
            job = new ThreadedTruncateJob(currentJobId.getAndIncrement(), this, 
                    trIter, tl, filterUpdate);
            break;
        case SEED:
            job = new ThreadedSeedJob(currentJobId.getAndIncrement(), threadCount,
                    this, false, trIter, tl, tileFailureRetryCount, tileFailureRetryWaitTime,
                    totalFailuresBeforeAborting,filterUpdate);
            break;
        case RESEED:
            job = new ThreadedSeedJob(currentJobId.getAndIncrement(), threadCount,
                    this, true, trIter, tl, tileFailureRetryCount, tileFailureRetryWaitTime,
                    totalFailuresBeforeAborting,filterUpdate);
            break;
        default:
            throw new IllegalArgumentException("Only SEED, RESEED, and TRUNCATE job types can be created.");
        }
        jobs.add(job);
        return job;
    }

    /**
     * Dispatches tasks 
     * 
     * @param job
     */
    public void dispatchJob(Job job) {
        Assert.isTrue(job.getBreeder()==this, "Job was not created by this breeder.");
        lock.writeLock().lock();
        try {
            GWCTask[] tasks = job.getTasks();
            for (int i = 0; i < tasks.length; i++) {
                final Long taskId = this.currentTaskId.incrementAndGet();
                final GWCTask task = tasks[i];
                Future<GWCTask> future = threadPool.submit(wrapTask(task));
                this.currentPool.put(taskId, new SubmittedTask(task, future));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Method returns List of Strings representing the status of the currently running and scheduled
     * threads
     * 
     * @return array of {@code [[tilesDone, tilesTotal, tilesRemaining, taskID, taskStatus],...]}
     *         where {@code taskStatus} is one of:
     *         {@code 0 = PENDING, 1 = RUNNING, 2 = DONE, -1 = ABORTED}
     */
    public long[][] getStatusList() {
        return getStatusList(null);
    }
    
    
    public Collection<TaskStatus> getTaskStatusList(String layerName){
        List<TaskStatus> list = new ArrayList<TaskStatus>(currentPool.size());
        lock.readLock().lock();
        try {
            for (Entry<Long, SubmittedTask> entry: currentPool.entrySet()) {
                GWCTask task = entry.getValue().task;
                if (layerName != null && !layerName.equals(task.getLayerName())) {
                    continue;
                }
                list.add( task.getStatus());
            }
        } finally {
            lock.readLock().unlock();
            this.drain();
        }
        return list;
    }

    public Collection<JobStatus> getJobStatusList() {
        return getJobStatusList(null);
    }

    public Collection<JobStatus> getJobStatusList(String layerName) {
        List<JobStatus> list = new ArrayList<JobStatus>(jobs.size());
        lock.readLock().lock();
        try {
            for (ThreadedJob job: jobs) {
                if (layerName != null && !layerName.equals(job.getLayer().getName())) {
                    continue;
                }
                list.add(job.getStatus());
            }
        } finally {
            lock.readLock().unlock();
            this.drain();
        }
        return list;
    }

    public Collection<TaskStatus> getTaskStatusList() {
        return getTaskStatusList(null);
    }

    /**
     * Method returns List of Strings representing the status of the currently running and scheduled
     * threads for a specific layer.
     * 
     * @return array of {@code [[tilesDone, tilesTotal, tilesRemaining, taskID, taskStatus],...]}
     *         where {@code taskStatus} is one of:
     *         {@code 0 = PENDING, 1 = RUNNING, 2 = DONE, -1 = ABORTED}
     * @param layerName the name of the layer.  null for all layers.
     * @return
     */
    public long[][] getStatusList(final String layerName) {
        List<long[]> list = new ArrayList<long[]>(currentPool.size());

        lock.readLock().lock();
        try {
            Iterator<Entry<Long, SubmittedTask>> iter = currentPool.entrySet().iterator();
            while (iter.hasNext()) {
                Entry<Long, SubmittedTask> entry = iter.next();
                GWCTask task = entry.getValue().task;
                if (layerName != null && !layerName.equals(task.getLayerName())) {
                    continue;
                }
                long[] ret = new long[5];
                ret[0] = task.getTilesDone();
                ret[1] = task.getTilesTotal();
                ret[2] = task.getTimeRemaining();
                ret[3] = task.getTaskId();
                ret[4] = stateCode(task.getState());
                list.add(ret);
            }
        } finally {
            lock.readLock().unlock();
            this.drain();
        }

        long[][] ret = list.toArray(new long[list.size()][]);
        return ret;
    }

    private long stateCode(STATE state) {
        switch (state) {
        case UNSET:
        case READY:
            return 0;
        case RUNNING:
            return 1;
        case DONE:
            return 2;
        case DEAD:
            return -1;
        default:
            throw new IllegalArgumentException("Unknown state: " + state);
        }
    }

    /**
     * Remove all inactive tasks from the current pool
     */
    private void drain() {
        lock.writeLock().lock();
        try {
            threadPool.purge();
            for (Iterator<Entry<Long, SubmittedTask>> it = this.currentPool.entrySet().iterator(); it
                    .hasNext();) {
                if (it.next().getValue().future.isDone()) {
                    it.remove();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setThreadPoolExecutor(SeederThreadPoolExecutor stpe) {
        threadPool = stpe;
    }

    /**
     * Find a layer by name.
     * @param layerName
     * @return
     * @throws GeoWebCacheException
     */
    public TileLayer findTileLayer(String layerName) throws GeoWebCacheException {
        TileLayer layer = null;

        layer = getTileLayerDispatcher().getTileLayer(layerName);

        if (layer == null) {
            throw new GeoWebCacheException("Uknown layer: " + layerName);
        }

        return layer;
    }

    /**
     * Get all tasks that are running
     * @return
     */
    public Iterator<GWCTask> getRunningTasks() {
        drain();
        return filterTasks(STATE.RUNNING);
    }

    /**
     * Get all tasks that are running or waiting to run.
     * @return
     */
    public Iterator<GWCTask> getRunningAndPendingTasks() {
        drain();
        return filterTasks(STATE.READY, STATE.UNSET, STATE.RUNNING);
    }

    /**
     * Get all tasks that are waiting to run.
     * @return
     */
    public Iterator<GWCTask> getPendingTasks() {
        drain();
        return filterTasks(STATE.READY, STATE.UNSET);
    }

    /**
     * Return all current tasks that are in the specified states
     * 
     * @param filter the states to filter for
     * @return
     */
    private Iterator<GWCTask> filterTasks(STATE... filter) {
        Set<STATE> states = new HashSet<STATE>(Arrays.asList(filter));
        lock.readLock().lock();
        List<GWCTask> runningTasks = new ArrayList<GWCTask>(this.currentPool.size());
        try {
            Collection<SubmittedTask> values = this.currentPool.values();
            for (SubmittedTask t : values) {
                GWCTask task = t.task;
                if (states.contains(task.getState())) {
                    runningTasks.add(task);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return runningTasks.iterator();
    }

    /**
     * Terminate a running or pending task
     * @param id
     * @return
     */
    public boolean terminateGWCTask(final long id) {
        SubmittedTask submittedTask = this.currentPool.remove(Long.valueOf(id));
        if (submittedTask == null) {
            return false;
        }
        submittedTask.task.terminateNicely();
        // submittedTask.future.cancel(true);
        return true;
    }

    /**
     * Get an iterator over the layers.
     * @return
     */
    public Iterable<TileLayer> getLayers() {
        return getTileLayerDispatcher().getLayerList();
    }

    protected long getNextTaskId() {
        return currentTaskId.getAndIncrement();
    }
    
    // Make visible to package.
    @Override
    protected void setTaskState(GWCTask task, GWCTask.STATE state) {
        super.setTaskState(task, state);
    }

    @Override
    protected SeedTask createSeedTask(SeedJob job) {
        return super.createSeedTask(job);
    }

    @Override
    protected TruncateTask createTruncateTask(TruncateJob job) {
        return super.createTruncateTask(job);
    }

}