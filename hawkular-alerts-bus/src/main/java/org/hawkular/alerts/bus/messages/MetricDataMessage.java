/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.alerts.bus.messages;

import java.util.List;

import com.google.gson.annotations.Expose;

import org.hawkular.bus.common.BasicMessage;

/**
 * A bus message for messages on HawkularMetricData Topic.
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */

public class MetricDataMessage extends BasicMessage {

    // the basic message body - it will be exposed to the JSON output
    @Expose
    private MetricData metricData;

    protected MetricDataMessage() {
    }

    public MetricDataMessage(MetricData metricData) {
        this.metricData = metricData;
    }

    public MetricData getMetricData() {
        return metricData;
    }

    public void setMetricData(MetricData metricData) {
        this.metricData = metricData;
    }

    public static class MetricData {
        @Expose
        String tenantId;
        @Expose
        List<SingleMetric> metrics;

        public MetricData() {
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public List<SingleMetric> getMetrics() {
            return metrics;
        }

        public void setMetrics(List<SingleMetric> metrics) {
            this.metrics = metrics;
        }

        @Override
        public String toString() {
            return "MetricData [tenantId=" + tenantId + ", metrics=" + metrics + "]";
        }
    }

    /**
     * This is meant to parse out an instance of <code>org.rhq.metrics.client.common.SingleMetric</code>
     */
    public static class SingleMetric {
        @Expose
        private String source;
        @Expose
        private long timestamp;
        @Expose
        private double value;
        @Expose
        private int metricType;

        public SingleMetric() {
        }

        public SingleMetric(String source, long timestamp, double value, int type) {
            this.source = source;
            this.timestamp = timestamp;
            this.value = value;
            this.metricType = type;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }

        public int getMetricType() {
            return metricType;
        }

        public void setMetricType(int type) {
            this.metricType = type;
        }

        @Override
        public String toString() {
            return "SingleMetric [source=" + source + ", timestamp=" + timestamp + ", value=" + value
                    + ", metricType=" + metricType + "]";
        }
    }
}