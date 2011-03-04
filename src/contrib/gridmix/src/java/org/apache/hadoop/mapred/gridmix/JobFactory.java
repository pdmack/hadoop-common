/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred.gridmix;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.gridmix.Statistics.ClusterStats;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.tools.rumen.JobStory;
import org.apache.hadoop.tools.rumen.JobStoryProducer;
import org.apache.hadoop.tools.rumen.Pre21JobHistoryConstants.Values;
import org.apache.hadoop.tools.rumen.TaskAttemptInfo;
import org.apache.hadoop.tools.rumen.TaskInfo;
import org.apache.hadoop.tools.rumen.ZombieJobProducer;
import org.apache.hadoop.tools.rumen.Pre21JobHistoryConstants;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Component reading job traces generated by Rumen. Each job in the trace is
 * assigned a sequence number and given a submission time relative to the
 * job that preceded it. Jobs are enqueued in the JobSubmitter provided at
 * construction.
 * @see org.apache.hadoop.tools.rumen.HadoopLogsAnalyzer
 */
abstract class JobFactory<T> implements Gridmix.Component<Void>,StatListener<T>{

  public static final Log LOG = LogFactory.getLog(JobFactory.class);

  protected final Path scratch;
  protected final float rateFactor;
  protected final Configuration conf;
  protected final Thread rThread;
  protected final AtomicInteger sequence;
  protected final JobSubmitter submitter;
  protected final CountDownLatch startFlag;
  protected final UserResolver userResolver;
  protected volatile IOException error = null;
  protected final JobStoryProducer jobProducer;
  protected final ReentrantLock lock = new ReentrantLock(true);

  /**
   * Creating a new instance does not start the thread.
   * @param submitter Component to which deserialized jobs are passed
   * @param jobTrace Stream of job traces with which to construct a
   *                 {@link org.apache.hadoop.tools.rumen.ZombieJobProducer}
   * @param scratch Directory into which to write output from simulated jobs
   * @param conf Config passed to all jobs to be submitted
   * @param startFlag Latch released from main to start pipeline
   * @throws java.io.IOException
   */
  public JobFactory(JobSubmitter submitter, InputStream jobTrace,
      Path scratch, Configuration conf, CountDownLatch startFlag,
      UserResolver userResolver) throws IOException {
    this(submitter, new ZombieJobProducer(jobTrace, null), scratch, conf,
        startFlag, userResolver);
  }

  /**
   * Constructor permitting JobStoryProducer to be mocked.
   * @param submitter Component to which deserialized jobs are passed
   * @param jobProducer Producer generating JobStory objects.
   * @param scratch Directory into which to write output from simulated jobs
   * @param conf Config passed to all jobs to be submitted
   * @param startFlag Latch released from main to start pipeline
   */
  protected JobFactory(JobSubmitter submitter, JobStoryProducer jobProducer,
      Path scratch, Configuration conf, CountDownLatch startFlag,
      UserResolver userResolver) {
    sequence = new AtomicInteger(0);
    this.scratch = scratch;
    this.rateFactor = conf.getFloat(Gridmix.GRIDMIX_SUB_MUL, 1.0f);
    this.jobProducer = jobProducer;
    this.conf = new Configuration(conf);
    this.submitter = submitter;
    this.startFlag = startFlag;
    this.rThread = createReaderThread();
    if(LOG.isDebugEnabled()) {
      LOG.debug(" The submission thread name is " + rThread.getName());
    }
    this.userResolver = userResolver;
  }


  static class MinTaskInfo extends TaskInfo {
    public MinTaskInfo(TaskInfo info) {
      super(info.getInputBytes(), info.getInputRecords(),
            info.getOutputBytes(), info.getOutputRecords(),
            info.getTaskMemory());
    }
    public long getInputBytes() {
      return Math.max(0, super.getInputBytes());
    }
    public int getInputRecords() {
      return Math.max(0, super.getInputRecords());
    }
    public long getOutputBytes() {
      return Math.max(0, super.getOutputBytes());
    }
    public int getOutputRecords() {
      return Math.max(0, super.getOutputRecords());
    }
    public long getTaskMemory() {
      return Math.max(0, super.getTaskMemory());
    }
  }

  protected static class FilterJobStory implements JobStory {

    protected final JobStory job;

    public FilterJobStory(JobStory job) {
      this.job = job;
    }
    public JobConf getJobConf() { return job.getJobConf(); }
    public String getName() { return job.getName(); }
    public JobID getJobID() { return job.getJobID(); }
    public String getUser() { return job.getUser(); }
    public long getSubmissionTime() { return job.getSubmissionTime(); }
    public InputSplit[] getInputSplits() { return job.getInputSplits(); }
    public int getNumberMaps() { return job.getNumberMaps(); }
    public int getNumberReduces() { return job.getNumberReduces(); }
    public TaskInfo getTaskInfo(TaskType taskType, int taskNumber) {
      return job.getTaskInfo(taskType, taskNumber);
    }
    public TaskAttemptInfo getTaskAttemptInfo(TaskType taskType, int taskNumber,
        int taskAttemptNumber) {
      return job.getTaskAttemptInfo(taskType, taskNumber, taskAttemptNumber);
    }
    public TaskAttemptInfo getMapTaskAttemptInfoAdjusted(
        int taskNumber, int taskAttemptNumber, int locality) {
      return job.getMapTaskAttemptInfoAdjusted(
          taskNumber, taskAttemptNumber, locality);
    }
    public Values getOutcome() {
      return job.getOutcome();
    }
  }

  protected abstract Thread createReaderThread() ;

  protected JobStory getNextJobFiltered() throws IOException {
    JobStory job;
    do {
      job = jobProducer.getNextJob();
    } while (job != null
        && (job.getOutcome() != Pre21JobHistoryConstants.Values.SUCCESS ||
            job.getSubmissionTime() < 0));
    return null == job ? null : new FilterJobStory(job) {
        @Override
        public TaskInfo getTaskInfo(TaskType taskType, int taskNumber) {
          return new MinTaskInfo(this.job.getTaskInfo(taskType, taskNumber));
         }
      };
   }
     
  /**
   * Obtain the error that caused the thread to exit unexpectedly.
   */
  public IOException error() {
    return error;
  }

  /**
   * Add is disabled.
   * @throws UnsupportedOperationException
   */
  public void add(Void ignored) {
    throw new UnsupportedOperationException(getClass().getName() +
        " is at the start of the pipeline and accepts no events");
  }

  /**
   * Start the reader thread, wait for latch if necessary.
   */
  public void start() {
    rThread.start();
  }

  /**
   * Wait for the reader thread to exhaust the job trace.
   */
  public void join(long millis) throws InterruptedException {
    rThread.join(millis);
  }

  /**
   * Interrupt the reader thread.
   */
  public void shutdown() {
    rThread.interrupt();
  }

  /**
   * Interrupt the reader thread. This requires no special consideration, as
   * the thread has no pending work queue.
   */
  public void abort() {
    // Currently no special work
    rThread.interrupt();
  }

}
