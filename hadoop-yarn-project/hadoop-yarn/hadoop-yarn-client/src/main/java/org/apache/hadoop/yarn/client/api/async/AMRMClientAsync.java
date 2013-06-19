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

package org.apache.hadoop.yarn.client.api.async;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Stable;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.Token;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.impl.AMRMClientAsyncImpl;
import org.apache.hadoop.yarn.client.api.impl.AMRMClientImpl;
import org.apache.hadoop.yarn.exceptions.YarnException;

import com.google.common.annotations.VisibleForTesting;

/**
 * <code>AMRMClientAsync</code> handles communication with the ResourceManager
 * and provides asynchronous updates on events such as container allocations and
 * completions.  It contains a thread that sends periodic heartbeats to the
 * ResourceManager.
 * 
 * It should be used by implementing a CallbackHandler:
 * <pre>
 * {@code
 * class MyCallbackHandler implements AMRMClientAsync.CallbackHandler {
 *   public void onContainersAllocated(List<Container> containers) {
 *     [run tasks on the containers]
 *   }
 *   
 *   public void onContainersCompleted(List<ContainerStatus> statuses) {
 *     [update progress, check whether app is done]
 *   }
 *   
 *   public void onNodesUpdated(List<NodeReport> updated) {}
 *   
 *   public void onReboot() {}
 * }
 * }
 * </pre>
 * 
 * The client's lifecycle should be managed similarly to the following:
 * 
 * <pre>
 * {@code
 * AMRMClientAsync asyncClient = 
 *     createAMRMClientAsync(appAttId, 1000, new MyCallbackhandler());
 * asyncClient.init(conf);
 * asyncClient.start();
 * RegisterApplicationMasterResponse response = asyncClient
 *    .registerApplicationMaster(appMasterHostname, appMasterRpcPort,
 *       appMasterTrackingUrl);
 * asyncClient.addContainerRequest(containerRequest);
 * [... wait for application to complete]
 * asyncClient.unregisterApplicationMaster(status, appMsg, trackingUrl);
 * asyncClient.stop();
 * }
 * </pre>
 */
@Public
@Stable
public abstract class AMRMClientAsync<T extends ContainerRequest> 
extends AbstractService {
  
  protected final AMRMClient<T> client;
  protected final CallbackHandler handler;
  protected final AtomicInteger heartbeatIntervalMs = new AtomicInteger();

  public static <T extends ContainerRequest> AMRMClientAsync<T> 
  createAMRMClientAsync(
      ApplicationAttemptId id, 
      int intervalMs, 
      CallbackHandler callbackHandler) {
    return new AMRMClientAsyncImpl<T>(id, intervalMs, callbackHandler);
  }
  
  public static <T extends ContainerRequest> AMRMClientAsync<T> 
  createAMRMClientAsync(
      AMRMClient<T> client, 
      int intervalMs, 
      CallbackHandler callbackHandler) {
    return new AMRMClientAsyncImpl<T>(client, intervalMs, callbackHandler);
  }
  
  protected AMRMClientAsync(ApplicationAttemptId id, int intervalMs,
      CallbackHandler callbackHandler) {
    this(new AMRMClientImpl<T>(id), intervalMs, callbackHandler);
  }
  
  @Private
  @VisibleForTesting
  protected AMRMClientAsync(AMRMClient<T> client, int intervalMs,
      CallbackHandler callbackHandler) {
    super(AMRMClientAsync.class.getName());
    this.client = client;
    this.heartbeatIntervalMs.set(intervalMs);
    this.handler = callbackHandler;
  }
    
  public void setHeartbeatInterval(int interval) {
    heartbeatIntervalMs.set(interval);
  }
  
  public abstract List<? extends Collection<T>> getMatchingRequests(
                                                   Priority priority, 
                                                   String resourceName, 
                                                   Resource capability);
  
  /**
   * Registers this application master with the resource manager. On successful
   * registration, starts the heartbeating thread.
   * @throws YarnException
   * @throws IOException
   */
  public abstract RegisterApplicationMasterResponse registerApplicationMaster(
      String appHostName, int appHostPort, String appTrackingUrl)
      throws YarnException, IOException;

  /**
   * Unregister the application master. This must be called in the end.
   * @param appStatus Success/Failure status of the master
   * @param appMessage Diagnostics message on failure
   * @param appTrackingUrl New URL to get master info
   * @throws YarnException
   * @throws IOException
   */
  public abstract void unregisterApplicationMaster(
      FinalApplicationStatus appStatus, String appMessage, String appTrackingUrl) 
  throws YarnException, IOException;

  /**
   * Request containers for resources before calling <code>allocate</code>
   * @param req Resource request
   */
  public abstract void addContainerRequest(T req);

  /**
   * Remove previous container request. The previous container request may have 
   * already been sent to the ResourceManager. So even after the remove request 
   * the app must be prepared to receive an allocation for the previous request 
   * even after the remove request
   * @param req Resource request
   */
  public abstract void removeContainerRequest(T req);

  /**
   * Release containers assigned by the Resource Manager. If the app cannot use
   * the container or wants to give up the container then it can release them.
   * The app needs to make new requests for the released resource capability if
   * it still needs it. eg. it released non-local resources
   * @param containerId
   */
  public abstract void releaseAssignedContainer(ContainerId containerId);

  /**
   * Get the currently available resources in the cluster.
   * A valid value is available after a call to allocate has been made
   * @return Currently available resources
   */
  public abstract Resource getAvailableResources();

  /**
   * Get the current number of nodes in the cluster.
   * A valid values is available after a call to allocate has been made
   * @return Current number of nodes in the cluster
   */
  public abstract int getClusterNodeCount();

  /**
   * It returns the NMToken received on allocate call. It will not communicate
   * with RM to get NMTokens. On allocate call whenever we receive new token
   * along with new container AMRMClientAsync will cache this NMToken per node
   * manager. This map returned should be shared with any application which is
   * communicating with NodeManager (ex. NMClient / NMClientAsync) using
   * NMTokens. If a new NMToken is received for the same node manager
   * then it will be replaced. 
   */
  public abstract ConcurrentMap<String, Token> getNMTokens();
  
  public interface CallbackHandler {
    
    /**
     * Called when the ResourceManager responds to a heartbeat with completed
     * containers. If the response contains both completed containers and
     * allocated containers, this will be called before containersAllocated.
     */
    public void onContainersCompleted(List<ContainerStatus> statuses);
    
    /**
     * Called when the ResourceManager responds to a heartbeat with allocated
     * containers. If the response containers both completed containers and
     * allocated containers, this will be called after containersCompleted.
     */
    public void onContainersAllocated(List<Container> containers);
    
    /**
     * Called when the ResourceManager wants the ApplicationMaster to shutdown
     * for being out of sync etc. The ApplicationMaster should not unregister
     * with the RM unless the ApplicationMaster wants to be the last attempt.
     */
    public void onShutdownRequest();
    
    /**
     * Called when nodes tracked by the ResourceManager have changed in health,
     * availability etc.
     */
    public void onNodesUpdated(List<NodeReport> updatedNodes);
    
    public float getProgress();
    
    public void onError(Exception e);
  }
}
