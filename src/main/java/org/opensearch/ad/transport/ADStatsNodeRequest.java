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
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import java.io.IOException;

import org.opensearch.action.support.nodes.BaseNodeRequest;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;

/**
 *  ADStatsNodeRequest to get a nodes stat
 */
public class ADStatsNodeRequest extends BaseNodeRequest {
    private ADStatsRequest request;

    /**
     * Constructor
     */
    public ADStatsNodeRequest() {
        super();
    }

    public ADStatsNodeRequest(StreamInput in) throws IOException {
        super(in);
        this.request = new ADStatsRequest(in);
    }

    /**
     * Constructor
     *
     * @param request ADStatsRequest
     */
    public ADStatsNodeRequest(ADStatsRequest request) {
        this.request = request;
    }

    /**
     * Get ADStatsRequest
     *
     * @return ADStatsRequest for this node
     */
    public ADStatsRequest getADStatsRequest() {
        return request;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        request.writeTo(out);
    }
}
