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

import java.util.Properties

import kafka.api.ApiVersion
import kafka.log.{LogConfig, LogManager}
import kafka.server.QuotaFactory.QuotaType
import kafka.utils.Logging
import org.apache.kafka.common.metrics.Quota
import org.apache.kafka.common.protocol.ApiKeys

import scala.collection.Map
import scala.collection.JavaConverters._

/**
 * The ConfigHandler is used to process config change notifications received by the DynamicConfigManager
 */
trait ConfigHandler {
  def processConfigChanges(entityName: String, value: Properties)
}

/**
 * The TopicConfigHandler will process topic config changes in ZK.
 * The callback provides the topic name and the full properties set read from ZK
 */
class TopicConfigHandler(private val logManager: LogManager, kafkaConfig: KafkaConfig, val quotaManagers: Map[QuotaType.Value, ClientQuotaManager], throttledFetcherManager: ReplicaThrottleManager) extends ConfigHandler with Logging {

  def processConfigChanges(topic: String, topicConfig: Properties) {
    // Validate the compatibility of message format version.
    val configNameToExclude = Option(topicConfig.getProperty(LogConfig.MessageFormatVersionProp)).flatMap { versionString =>
      if (kafkaConfig.interBrokerProtocolVersion < ApiVersion(versionString)) {
        warn(s"Log configuration ${LogConfig.MessageFormatVersionProp} is ignored for `$topic` because `$versionString` " +
          s"is not compatible with Kafka inter-broker protocol version `${kafkaConfig.interBrokerProtocolVersionString}`")
        Some(LogConfig.MessageFormatVersionProp)
      } else
        None
    }

    val logs = logManager.logsByTopicPartition.filterKeys(_.topic == topic).values.toBuffer
    if (logs.nonEmpty) {
      /* combine the default properties with the overrides in zk to create the new LogConfig */
      val props = new Properties()
      props.putAll(logManager.defaultConfig.originals)
      topicConfig.asScala.foreach { case (key, value) =>
        if (key != configNameToExclude) props.put(key, value)
      }
      val logConfig = LogConfig(props)
      logs.foreach(_.config = logConfig)
    }

    val brokerId: Int = kafkaConfig.brokerId

    if(topicConfig.containsKey(KafkaConfig.ReplicationQuotaThrottledReplicas)) {
      val partitions: Seq[Int] = parseThrottledPartitions(topicConfig, brokerId)
      logger.info("Setting throttled partitions on broker "+brokerId +  " to "+ partitions.map(_.toString))
      quotaManagers(QuotaType.LeaderReplication).throttledReplicas.updateThrottledPartitions(topic, partitions)
      throttledFetcherManager.updateThrottledPartitions(topic, partitions)
    }
  }

  def parseThrottledPartitions(topicConfig: Properties, brokerId: Int): Seq[Int] = {
    val throttlePartitionIds = topicConfig.get(KafkaConfig.ReplicationQuotaThrottledReplicas).toString
      .split(",")
      .filter(_.split("-")(1).toInt == brokerId) //match replica
      .map(_.split("-")(0).toInt).toSeq //match partition
    throttlePartitionIds
  }
}

object ClientConfigOverride {
  val ProducerOverride = "producer_byte_rate"
  val ConsumerOverride = "consumer_byte_rate"
}

//TODO this should be refactored/removed
object ReplicationConfigOverride {
  val QuotaOverride = "replication-quota"
}



/**
 * The ClientIdConfigHandler will process clientId config changes in ZK.
 * The callback provides the clientId and the full properties set read from ZK.
 * This implementation reports the overrides to the respective ClientQuotaManager objects
 */
class ClientIdConfigHandler(private val quotaManagers: Map[QuotaType.Value, ClientQuotaManager]) extends ConfigHandler {

  def processConfigChanges(clientId: String, clientConfig: Properties) = {
    if (clientConfig.containsKey(ClientConfigOverride.ProducerOverride)) {
      quotaManagers(QuotaType.Produce).updateQuota(clientId,
        new Quota(clientConfig.getProperty(ClientConfigOverride.ProducerOverride).toLong, true))
    }

    if (clientConfig.containsKey(ClientConfigOverride.ConsumerOverride)) {
      quotaManagers(QuotaType.Fetch).updateQuota(clientId,
        new Quota(clientConfig.getProperty(ClientConfigOverride.ConsumerOverride).toLong, true))

      //TODO - these should be moved to broker level configs later
      quotaManagers(QuotaType.LeaderReplication).updateQuota(clientId,
        new Quota(clientConfig.getProperty(ClientConfigOverride.ConsumerOverride).toLong, true))
      quotaManagers(QuotaType.FollowerReplication).updateQuota(clientId,
        new Quota(clientConfig.getProperty(ClientConfigOverride.ConsumerOverride).toLong, true))
    }
  }
}
