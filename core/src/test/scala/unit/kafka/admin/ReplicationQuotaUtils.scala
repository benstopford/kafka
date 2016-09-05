/**
  * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
  * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
  * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
  * License. You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
  * specific language governing permissions and limitations under the License.
  */
package unit.kafka.admin

import kafka.admin.AdminUtils
import kafka.log.LogConfig
import kafka.server.{KafkaConfig, ConfigType, KafkaServer}
import kafka.utils.TestUtils
import org.apache.kafka.clients.producer.ProducerRecord

import scala.collection.Seq

object ReplicationQuotaUtils {

  def addMessages(num: Int, size:Int = 1000, topic: String, servers: Seq[KafkaServer]): Unit ={
    val producer = TestUtils.createNewProducer(TestUtils.getBrokerListStrFromServers(servers), retries = 5, acks = 0)
    for (x <- 0 until num)
      producer.send(new ProducerRecord(topic, new Array[Byte](size))).get
  }

  def checkThrottleConfigRemovedFromZK(topic: String, servers: Seq[KafkaServer]): Boolean = {
    TestUtils.waitUntilTrue(() => {
      val brokerReset = servers.forall { server =>
        val brokerConfig = AdminUtils.fetchEntityConfig(server.zkUtils, ConfigType.Broker, server.config.brokerId.toString)
        Long.MaxValue.toString == brokerConfig.getProperty(KafkaConfig.ThrottledReplicationRateLimitProp)
      }
      val topicConfig = AdminUtils.fetchEntityConfig(servers(0).zkUtils, ConfigType.Topic, topic)
      val topicReset = ("" == topicConfig.getProperty(LogConfig.ThrottledReplicasListProp))
      brokerReset && topicReset
    }, "Throttle limit/replicas was not unset")
  }

  def checkThrottleConfigAddedToZK(expectedThrottleRate: Long, servers: Seq[KafkaServer], topic: String): Boolean = {
    TestUtils.waitUntilTrue(() => {
      //Check for limit in ZK
      val brokerConfigAvailable = servers.forall { server =>
        val configInZk = AdminUtils.fetchEntityConfig(server.zkUtils, ConfigType.Broker, server.config.brokerId.toString)
        val zkThrottleRate = configInZk.getProperty(KafkaConfig.ThrottledReplicationRateLimitProp)
        zkThrottleRate != null && expectedThrottleRate == zkThrottleRate.toLong
      }
      //Check replcias assigned
      val topicConfig = AdminUtils.fetchEntityConfig(servers(0).zkUtils, ConfigType.Topic, topic)
      val topicConfigAvailable = topicConfig.getProperty(LogConfig.ThrottledReplicasListProp) == "*"
      brokerConfigAvailable && topicConfigAvailable
    }, "throttle limit/replicas was not set")
  }
}