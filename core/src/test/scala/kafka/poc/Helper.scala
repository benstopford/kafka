package kafka.poc

import kafka.admin.BrokerMetadata
import kafka.common.TopicAndPartition

object Helper {

  val topic: String = "my-topic"
  def p(i: Int) = {
    new TopicAndPartition(topic, i)
  }

  def bk(id: Int, rack: String) = {
    new BrokerMetadata(id, Option(rack))
  }

}