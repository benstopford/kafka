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

package kafka.server

import kafka.cluster.BrokerEndPoint
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.utils.Time

class ThrottledReplicaFetcherManager(brokerConfig: KafkaConfig, replicaMgr: ReplicaManager, metrics: Metrics, time: Time, threadNamePrefix: Option[String] = None, quotaManager: ClientQuotaManager)
        extends AbstractFetcherManager("ThrottledFetcherManager on broker " + brokerConfig.brokerId,
                                       "ThrottledReplica", brokerConfig.numReplicaFetchers) {

  override def createFetcherThread(fetcherId: Int, sourceBroker: BrokerEndPoint): AbstractFetcherThread = {
    val threadName = threadNamePrefix match {
      case None =>
        "ThrottledReplicaFetcherThread-%d-%d".format(fetcherId, sourceBroker.id)
      case Some(p) =>
        "%s:ThrottledReplicaFetcherThread-%d-%d".format(p, fetcherId, sourceBroker.id)
    }
    new ReplicaFetcherThread(threadName, fetcherId +10000, sourceBroker, brokerConfig, //todo do something better than shifting by 10k
      replicaMgr, metrics, time, quotaManager, true)
  }

  def shutdown() {
    info("shutting down")
    closeAllFetchers()
    info("shutdown completed")
  }
}
