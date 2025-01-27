/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.ad.transport;

import static org.opensearch.ad.model.ADTask.ERROR_FIELD;
import static org.opensearch.ad.model.ADTask.STATE_FIELD;
import static org.opensearch.ad.model.ADTask.TASK_PROGRESS_FIELD;

import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionListener;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.ad.model.ADTask;
import org.opensearch.ad.model.ADTaskAction;
import org.opensearch.ad.model.ADTaskState;
import org.opensearch.ad.model.AnomalyDetector;
import org.opensearch.ad.model.DetectionDateRange;
import org.opensearch.ad.task.ADTaskCacheManager;
import org.opensearch.ad.task.ADTaskManager;
import org.opensearch.common.inject.Inject;
import org.opensearch.rest.RestStatus;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import com.google.common.collect.ImmutableMap;

public class ForwardADTaskTransportAction extends HandledTransportAction<ForwardADTaskRequest, AnomalyDetectorJobResponse> {
    private final Logger logger = LogManager.getLogger(ForwardADTaskTransportAction.class);
    private final TransportService transportService;
    private final ADTaskManager adTaskManager;
    private final ADTaskCacheManager adTaskCacheManager;

    @Inject
    public ForwardADTaskTransportAction(
        ActionFilters actionFilters,
        TransportService transportService,
        ADTaskManager adTaskManager,
        ADTaskCacheManager adTaskCacheManager
    ) {
        super(ForwardADTaskAction.NAME, transportService, actionFilters, ForwardADTaskRequest::new);
        this.adTaskManager = adTaskManager;
        this.transportService = transportService;
        this.adTaskCacheManager = adTaskCacheManager;
    }

    @Override
    protected void doExecute(Task task, ForwardADTaskRequest request, ActionListener<AnomalyDetectorJobResponse> listener) {
        ADTaskAction adTaskAction = request.getAdTaskAction();
        AnomalyDetector detector = request.getDetector();
        DetectionDateRange detectionDateRange = request.getDetectionDateRange();
        String detectorId = detector.getDetectorId();
        ADTask adTask = request.getAdTask();
        String categoryField = detector.isMultientityDetector() ? detector.getCategoryField().get(0) : null;
        String entityValue = detector.isMultientityDetector() && adTask != null
            ? adTask.getEntity().getAttributes().get(categoryField)
            : null;

        switch (adTaskAction) {
            case START:
                // Start historical analysis for detector
                adTaskManager.startHistoricalAnalysisTask(detector, detectionDateRange, request.getUser(), transportService, listener);
                break;
            case FINISHED:
                // Historical analysis finished, so we need to remove detector cache. Only single entity detectors use this.
                adTaskManager.removeDetectorFromCache(request.getDetector().getDetectorId());
                listener.onResponse(new AnomalyDetectorJobResponse(detector.getDetectorId(), 0, 0, 0, RestStatus.OK));
                break;
            case NEXT_ENTITY:
                // Run next entity for HC detector historical analysis.
                logger.debug("Received task for NEXT_ENTITY action: {}", adTask.getTaskId());
                if (detector.isMultientityDetector()) { // AD task could be HC detector level task or entity task
                    adTaskCacheManager.removeRunningEntity(detectorId, entityValue);
                    if (!adTaskCacheManager.hasEntity(detectorId)) {
                        logger.info("Historical HC detector done, will remove from cache, detector id:{}", detectorId);
                        listener.onResponse(new AnomalyDetectorJobResponse(detectorId, 0, 0, 0, RestStatus.OK));
                        // TODO: reset task state when get task
                        ADTaskState state = !adTask.isEntityTask() && adTask.getError() != null ? ADTaskState.FAILED : ADTaskState.FINISHED;
                        adTaskManager.setHCDetectorTaskDone(adTask, state, listener);
                    } else {
                        logger.debug("Run next entity for detector " + detectorId);
                        adTaskManager.runNextEntityForHCADHistorical(adTask, listener);
                        adTaskManager
                            .updateADHCDetectorTask(
                                detectorId,
                                adTask.getParentTaskId(),
                                ImmutableMap
                                    .of(
                                        STATE_FIELD,
                                        ADTaskState.RUNNING.name(),
                                        TASK_PROGRESS_FIELD,
                                        adTaskManager.hcDetectorProgress(detectorId),
                                        ERROR_FIELD,
                                        adTask.getError() != null ? adTask.getError() : ""
                                    )
                            );
                    }
                } else {
                    logger
                        .warn(
                            "Can only handle HC entity task for NEXT_ENTITY action, taskId:{} , taskType:{}",
                            adTask.getTaskId(),
                            adTask.getTaskType()
                        );
                    listener.onFailure(new IllegalArgumentException("Unsupported task"));
                }
                break;
            case PUSH_BACK_ENTITY:
                // Push back entity to pending entities queue and run next entity.
                if (adTask.isEntityTask()) { // AD task must be entity level task.
                    if (adTaskManager.isRetryableError(adTask.getError())
                        && !adTaskCacheManager.exceedRetryLimit(adTask.getDetectorId(), adTask.getTaskId())) {
                        // If retryable exception happens when run entity task, will push back entity to the end
                        // of pending entities queue, then we can retry it later.
                        adTaskCacheManager.pushBackEntity(adTask.getTaskId(), adTask.getDetectorId(), entityValue);
                    } else {
                        // If exception is not retryable or exceeds retry limit, will remove this entity.
                        adTaskCacheManager.removeEntity(adTask.getDetectorId(), entityValue);
                        logger.warn("Entity task failed, task id: {}", adTask.getTaskId());
                    }
                    adTaskCacheManager.removeRunningEntity(detectorId, entityValue);
                    if (!adTaskCacheManager.hasEntity(detectorId)) {
                        adTaskManager.setHCDetectorTaskDone(adTask, ADTaskState.FINISHED, listener);
                    } else {
                        adTaskManager.runNextEntityForHCADHistorical(adTask, listener);
                    }
                } else {
                    logger.warn("Can only push back entity task");
                    listener.onFailure(new IllegalArgumentException("Can only push back entity task"));
                }
                break;
            case CANCEL:
                // Cancel HC detector's historical analysis.
                // Don't support single detector for this action as single entity task will be cancelled directly
                // on worker node.
                if (detector.isMultientityDetector()) {
                    adTaskCacheManager.clearPendingEntities(detectorId);
                    adTaskCacheManager.removeRunningEntity(detectorId, entityValue);
                    if (!adTaskCacheManager.hasEntity(detectorId) || !adTask.isEntityTask()) {
                        adTaskManager.setHCDetectorTaskDone(adTask, ADTaskState.STOPPED, listener);
                    }
                    listener.onResponse(new AnomalyDetectorJobResponse(adTask.getTaskId(), 0, 0, 0, RestStatus.OK));
                } else {
                    listener.onFailure(new IllegalArgumentException("Only support cancel HC now"));
                }
                break;
            case CLEAN_STALE_RUNNING_ENTITIES:
                // Clean stale running entities of HC detector. For example, some worker node crashed or failed to send
                // entity task done message to coordinating node, then coordinating node can't remove running entity
                // from cache. We will check task profile when get task. If some entities exist in coordinating cache but
                // doesn't exist in worker node's cache, we will clean up these stale running entities on coordinating node.
                List<String> staleRunningEntities = request.getStaleRunningEntities();
                logger
                    .debug(
                        "Clean stale running entities of task {}, staleRunningEntities: {}",
                        adTask.getTaskId(),
                        Arrays.toString(staleRunningEntities.toArray(new String[0]))
                    );
                for (String entity : staleRunningEntities) {
                    adTaskManager.removeStaleRunningEntity(adTask, entity, listener);
                }
                listener.onResponse(new AnomalyDetectorJobResponse(adTask.getTaskId(), 0, 0, 0, RestStatus.OK));
                break;
            default:
                listener.onFailure(new OpenSearchStatusException("Unsupported AD task action " + adTaskAction, RestStatus.BAD_REQUEST));
                break;
        }

    }
}
