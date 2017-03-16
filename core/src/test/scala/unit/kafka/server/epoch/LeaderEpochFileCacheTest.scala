package kafka.server.epoch
import java.io.File

import kafka.server.LogOffsetMetadata
import kafka.server.checkpoints.{LeaderEpochCheckpoint, LeaderEpochCheckpointFile}
import kafka.server.epoch.Constants.{UNSUPPORTED_EPOCH, UNSUPPORTED_EPOCH_OFFSET}
import kafka.utils.TestUtils
import org.junit.Assert._
import org.junit.{Before, Test}

import scala.collection.mutable.ListBuffer

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
class LeaderEpochFileCacheTest {
  var checkpoint: LeaderEpochCheckpoint = _

  @Test
  def shouldUpdateEpochWithLeo() = {
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    leo = 9
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When
    cache.maybeUpdate(2);

    //Then
    assertEquals(2, cache.latestEpoch())
    assertEquals(EpochEntry(2, 9), cache.epochs(0))
  }

  @Test
  def shouldUpdateEpochWithMessageOffset() = {
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When
    cache.maybeUpdate(epoch = 2, offset = 10); leo = 11

    //Then
    assertEquals(2, cache.latestEpoch())
    assertEquals(EpochEntry(2, 10), cache.epochs(0))
    assertEquals(11, cache.endOffsetFor(2))
  }

  @Test
  def shouldReturnUnsupportedIfNoEpochRecorded(){
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //Then
    assertEquals(UNSUPPORTED_EPOCH, cache.latestEpoch())
    assertEquals(UNSUPPORTED_EPOCH_OFFSET, cache.endOffsetFor(0))
  }

  @Test
  def shouldGetLogEndOffsetInRequestForTheCurrentEpoch() = {
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When just one epoch
    cache.maybeUpdate(epoch = 2, offset = 11);
    cache.maybeUpdate(epoch = 2, offset = 12);
    leo = 13

    //Then
    assertEquals(13, cache.endOffsetFor(2))
  }

  @Test
  def shouldGetFirstOffsetOfSubsequentEpochWhenOffsetRequestedForPreviousEpoch() = {
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When several epochs
    cache.maybeUpdate(epoch = 1, offset = 11);
    cache.maybeUpdate(epoch = 1, offset = 12);
    cache.maybeUpdate(epoch = 2, offset = 13);
    cache.maybeUpdate(epoch = 2, offset = 14);
    cache.maybeUpdate(epoch = 3, offset = 15);
    cache.maybeUpdate(epoch = 3, offset = 16);
    leo = 17

    //Then get the start offset of the next epoch
    assertEquals(15, cache.endOffsetFor(2))
  }


  @Test
  def shouldReturnNextAvailableEpochIfThereIsNoExactEpochForTheOneRequested(){
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When
    cache.maybeUpdate(epoch = 0, offset = 10)
    cache.maybeUpdate(epoch = 2, offset = 13)
    cache.maybeUpdate(epoch = 4, offset = 17)
    leo = 3

    println(cache.epochs)

    //Then
    assertEquals(13, cache.endOffsetFor(requestedEpoch = 1))
    assertEquals(17, cache.endOffsetFor(requestedEpoch = 2))
  }

  @Test
  def shouldNotUpdateEpochAndStartOffsetIfItDidNotChange() = {
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When
    cache.maybeUpdate(epoch = 2, offset = 6); leo = 7
    cache.maybeUpdate(epoch = 2, offset = 7); leo = 8

    //Then
    assertEquals(1, cache.epochs.size)
    assertEquals(EpochEntry(2, 6), cache.epochs(0))
  }

  @Test
  def shouldReturnInvalidOffsetIfEpochIsRequestedWhichIsNotCurrentlyTracked(): Unit ={
    val leo = 100
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When
    cache.maybeUpdate(epoch = 2)

    //Then
    assertEquals(UNSUPPORTED_EPOCH_OFFSET, cache.endOffsetFor(3))
  }

  @Test
  def shouldSupportEpochsThatDontStartFromZero(): Unit ={
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When
    cache.maybeUpdate(epoch = 2, offset = 6); leo = 7

    //Then
    assertEquals(7, cache.endOffsetFor(2))
    assertEquals(1, cache.epochs.size)
    assertEquals(EpochEntry(2, 6), cache.epochs(0))
  }

  @Test
  def shouldPersistEpochsBetweenInstances(){
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(0)
    val checkpointPath = TestUtils.tempFile().getAbsolutePath
    checkpoint = new LeaderEpochCheckpointFile(new File(checkpointPath))

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)
    cache.maybeUpdate(epoch = 2, offset = 6);

    //When
    val checkpoint2 = new LeaderEpochCheckpointFile(new File(checkpointPath))
    val cache2 = new LeaderEpochFileCache(() => leoFinder, checkpoint2)

    //Then
    assertEquals(1, cache2.epochs.size)
    assertEquals(EpochEntry(2, 6), cache2.epochs(0))
  }

  @Test  //TODO double check Jun agrees with this logic
  def shouldNeverLetEpochGoBackwardsEvenIfMessageEpochsDo(): Unit ={
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //Given
    cache.maybeUpdate(epoch = 1, offset = 5); leo = 6
    cache.maybeUpdate(epoch = 2, offset = 6); leo = 7

    //When we update an epoch in the past with an earlier offset
    cache.maybeUpdate(epoch = 1, offset = 7); leo = 8

    //Then epoch should not be changed
    assertEquals(2, cache.latestEpoch())

    //Then end offset for epoch 1 shouldn't have changed
    assertEquals(6, cache.endOffsetFor(1))

    //Then end offset for epoch 2 has to be the offset of the epoch 1 message (I can't thing of a better option)
    assertEquals(8, cache.endOffsetFor(2))

    //Epoch history shouldn't have changed
    assertEquals(EpochEntry(1, 5), cache.epochs(0))
    assertEquals(EpochEntry(2, 6), cache.epochs(1))
  }

  @Test
  def shouldIncreaseAndTrackEpochAsLeadersChangeManyTimes(): Unit ={
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When
    cache.maybeUpdate(epoch = 1) //leo=0

    //Then epoch should go up
    assertEquals(1, cache.latestEpoch())
    //offset for 1 should still be 0
    assertEquals(0, cache.endOffsetFor(1))
    //offset for 0 should the start offset of epoch(1) => 0
    assertEquals(0, cache.endOffsetFor(0))

    //When we write 5 messages as epoch 1
    leo = 5

    //Then end offset for epoch(1) should be leo => 5
    assertEquals(5, cache.endOffsetFor(1))
    //Epoch(0) should still show the start offset for Epoch(1) => 0
    assertEquals(0, cache.endOffsetFor(0))

    //When
    cache.maybeUpdate(epoch = 2) //leo=5
    leo = 10 //write another 5 messages

    //Then end offset for epoch(2) should be leo => 10
    assertEquals(10, cache.endOffsetFor(2))

    //end offset for epoch(1) should be the start offset of epoch(2) => 5
    assertEquals(5, cache.endOffsetFor(1))

    //epoch (0) should still be 0
    assertEquals(0, cache.endOffsetFor(0))
  }

  @Test
  def shouldIncreaseAndTrackEpochAsFollowerReceivesManyMessages(): Unit ={
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //When new
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)

    //When Messages come in
    cache.maybeUpdate(epoch = 0, offset = 0); leo = 1
    cache.maybeUpdate(epoch = 0, offset = 1); leo = 2
    cache.maybeUpdate(epoch = 0, offset = 2); leo = 3

    //Then epoch should stay, offsets should grow
    assertEquals(0, cache.latestEpoch())
    assertEquals(leo, cache.endOffsetFor(0))

    //When messags arrive with greater epoch
    cache.maybeUpdate(epoch = 1, offset = 3); leo = 4
    cache.maybeUpdate(epoch = 1, offset = 4); leo = 5
    cache.maybeUpdate(epoch = 1, offset = 5); leo = 6

    assertEquals(1, cache.latestEpoch())
    assertEquals(leo, cache.endOffsetFor(1))

    //When
    cache.maybeUpdate(epoch = 2, offset = 6); leo = 7
    cache.maybeUpdate(epoch = 2, offset = 7); leo = 8
    cache.maybeUpdate(epoch = 2, offset = 8); leo = 9

    assertEquals(2, cache.latestEpoch())
    assertEquals(leo, cache.endOffsetFor(2))

    //Older epochs should return the start offset of the first message in the subsequent epoch.
    assertEquals(3, cache.endOffsetFor(0))
    assertEquals(6, cache.endOffsetFor(1))
  }

  @Test
  def shouldResetLeaderEpochBetweenEpochBoundary(): Unit ={
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)
    cache.maybeUpdate(epoch = 2, offset = 6);  leo = 7
    cache.maybeUpdate(epoch = 3, offset = 8);  leo = 9
    cache.maybeUpdate(epoch = 4, offset = 11); leo = 12

    //When reset to offset between epoch boundaries
    cache.resetTo(offset = 9)

    //Then should keep the preceding epochs
    assertEquals(3, cache.latestEpoch())
    assertEquals(ListBuffer(
      EpochEntry(2, 6),
      EpochEntry(3, 8)
    ), cache.epochs)
  }

  @Test
  def shouldResetLeaderEpochOnEpochBoundary(): Unit ={
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)
    cache.maybeUpdate(epoch = 2, offset = 6);  leo = 7
    cache.maybeUpdate(epoch = 3, offset = 8);  leo = 9
    cache.maybeUpdate(epoch = 4, offset = 11); leo = 12

    //When reset to offset on epoch boundary
    cache.resetTo(offset = 8)

    //Then should remove that epoch
    assertEquals(2, cache.latestEpoch())
    assertEquals(ListBuffer(EpochEntry(2, 6)), cache.epochs)
  }

  @Test
  def shouldNotResetEpochHistoryIfUndefinedPassed(): Unit ={
    var leo = 0
    def leoFinder(): LogOffsetMetadata = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(() => leoFinder, checkpoint)
    cache.maybeUpdate(epoch = 2, offset = 6);  leo = 7
    cache.maybeUpdate(epoch = 3, offset = 8);  leo = 9
    cache.maybeUpdate(epoch = 4, offset = 11); leo = 12

    //When reset to offset on epoch boundary
    cache.resetTo(offset = UNSUPPORTED_EPOCH_OFFSET)

    //Then should do nothing
    assertEquals(3, cache.epochs.size)
  }

  @Before
  def setUp() {
    checkpoint = new LeaderEpochCheckpointFile(TestUtils.tempFile())
  }
}