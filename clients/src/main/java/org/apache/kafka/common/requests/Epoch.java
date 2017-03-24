/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.requests;

/**
 * Data Transfer Object for the  Offsets for Leader Epoch Request.
 */

public class Epoch {
    private int partitionId;
    private int epoch;

    public Epoch(int partitionId, int epoch) {
        this.partitionId = partitionId;
        this.epoch = epoch;
    }

    public int partitionId() {
        return partitionId;
    }

    public int epoch() {
        return epoch;
    }

    @Override
    public String toString() {
        return "Epoch{" +
                "partitionId=" + partitionId +
                ", epoch=" + epoch +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Epoch epoch1 = (Epoch) o;

        if (partitionId != epoch1.partitionId) return false;
        return epoch == epoch1.epoch;

    }

    @Override
    public int hashCode() {
        int result = partitionId;
        result = 31 * result + epoch;
        return result;
    }
}