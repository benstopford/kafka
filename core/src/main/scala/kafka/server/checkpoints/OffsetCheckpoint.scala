/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package kafka.server.checkpoints

import java.io._
import java.util.regex.Pattern

import org.apache.kafka.common.TopicPartition

import scala.collection._

private object OffsetCheckpoint {
  val WhiteSpacesPattern = Pattern.compile("\\s+")
  val CurrentVersion = 0
}

/**
  * This class saves out a map of topic/partition=>offsets to a file
  */
class OffsetCheckpoint(val f: File)
  extends CommonCheckpointFile[(TopicPartition, Long)](f, OffsetCheckpoint.CurrentVersion)
{
  override def serialize(entry: (TopicPartition, Long)): String = {
    s"${entry._1.topic} ${entry._1.partition} ${entry._2}"
  }

  override def deserialze(line: String): Option[(TopicPartition, Long)] = {
    OffsetCheckpoint.WhiteSpacesPattern.split(line) match {
      case Array(topic, partition, offset) =>
        Some(new TopicPartition(topic, partition.toInt), offset.toLong)
      case _ => None
    }
  }

  def write(offsets: Map[TopicPartition, Long]) = {
    super.writeInternal(offsets.toSeq)
  }

  def read(): Map[TopicPartition, Long] = {
    readInternal().toMap
  }
}