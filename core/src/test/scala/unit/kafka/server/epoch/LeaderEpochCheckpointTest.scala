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
package unit.kafka.server.epoch

import java.io.File

import kafka.server.epoch.{EpochEntry, LeaderEpoch, LeaderEpochCheckpoint}
import kafka.utils.Logging
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.junit.Assert._

class LeaderEpochCheckpointTest extends JUnitSuite  with Logging{

  @Test
  def shouldPersistOverwriteAndReloadFile(): Unit ={
    val file = File.createTempFile(LeaderEpoch.LeaderEpochCheckpointFilename, null)
    val checkpoint = new LeaderEpochCheckpoint(file)

    //Given
    val epochs = Seq(EpochEntry(0, 1L), EpochEntry(1, 2L), EpochEntry(2, 3L))

    //When
    checkpoint.write(epochs)

    //Then
    assertEquals(epochs, checkpoint.read())

    //Given overwrite
    val epochs2 = Seq(EpochEntry(3, 4L), EpochEntry(4, 5L))

    //When
    checkpoint.write(epochs2)

    //Then
    assertEquals(epochs2, checkpoint.read())
  }

}
