package kafka.poc

import kafka.admin.BrokerMetadata
import kafka.common.TopicAndPartition
import kafka.poc.fairness.{LeaderFairness, ReplicaFairness, Fairness}

import scala.collection._
import collection.mutable.LinkedHashMap


class ClusterTopologyView(allBrokers: Seq[BrokerMetadata], allPartitions: Map[TopicAndPartition, Seq[Int]], rack: String) extends BaseSomething {

  def this(allBrokers: Seq[BrokerMetadata], allPartitions: Map[TopicAndPartition, Seq[Int]]) {
    this(allBrokers, allPartitions, null)
  }

  var partitions: Map[TopicAndPartition, Seq[Int]] = allPartitions
  var brokersToReplicas = createBrokersToReplicas(allBrokers, allBrokers, allPartitions)
  var brokersToLeaders = createBrokersToLeaders(allBrokers, allBrokers, allPartitions)

  var replicaFairness = new ReplicaFairness(brokersToReplicas, rackCount)
  var leaderFairness = new LeaderFairness(brokersToLeaders)
  val byRack = new ByRack()
  val byBroker = new ByBroker()

  refreshView(allPartitions, rack)

  //TODO make less ugly somehow
  def refreshView(p: Map[TopicAndPartition, Seq[Int]], rack: String): Unit = {
    var brokers = allBrokers
    if (rack != null) {
      brokers = allBrokers.filter(_.rack.get == rack)
      partitions = filter(rack, allBrokers, p)

      brokersToReplicas = createBrokersToReplicas(allBrokers, brokers, p).filter(_._1.rack.get == rack)
      brokersToLeaders = createBrokersToLeaders(allBrokers, brokers, p).filter(_._1.rack.get == rack)
    } else {
      brokersToReplicas = createBrokersToReplicas(allBrokers, allBrokers, p)
      brokersToLeaders = createBrokersToLeaders(allBrokers, allBrokers, p)
    }

    replicaFairness = new ReplicaFairness(brokersToReplicas, rackCount)
    leaderFairness = new LeaderFairness(brokersToLeaders)
  }

  class ByRack() extends ClusterView {
    def aboveParReplicas(): Seq[Replica] = replicaFairness.aboveParRacks.flatMap(weightedReplicasFor(_))

    def belowParBrokers(): Seq[BrokerMetadata] = replicaFairness.belowParRacks.flatMap(leastLoadedBrokerIds(_))

    def aboveParPartitions(): Seq[TopicAndPartition] = leaderFairness.aboveParRacks.flatMap(leadersOn(_))

    def brokersWithBelowParLeaders(): Seq[Int] = brokersOn(leaderFairness.belowParRacks)

    def refresh(newPartitions: Map[TopicAndPartition, Seq[Int]]) = refreshView(newPartitions, rack)
  }

  class ByBroker() extends ClusterView {
    def aboveParReplicas(): Seq[Replica] = replicaFairness.aboveParBrokers.flatMap(weightedReplicasFor(_))

    def belowParBrokers(): Seq[BrokerMetadata] = replicaFairness.belowParBrokers

    def aboveParPartitions(): Seq[TopicAndPartition] = leaderFairness.aboveParBrokers.flatMap(leadersOn(_))

    def brokersWithBelowParLeaders(): Seq[Int] = leaderFairness.belowParBrokers.map(_.id)

    def refresh(newPartitions: Map[TopicAndPartition, Seq[Int]]) = refreshView(newPartitions, rack)
  }


  def rackCount: Int = brokersToReplicas.map(_._1.rack).distinct.size

  def racks: Seq[String] = brokersToReplicas.map(_._1.rack.get).distinct

  def leastLoadedBrokerIds(): Seq[Int] = brokersToReplicas.map(_._1.id).reverse

  def replicaExists(replica: Any, rack: String): Boolean = brokersToReplicas.filter(_._1.rack.get == rack).map(_._2).size > 0

  private def replicasFor(broker: Int): Seq[Replica] = brokersToReplicas.filter(_._1.id == broker).seq(0)._2

  private def bk(id: Int): BrokerMetadata = allBrokers.filter(_.id == id).last


  object constraints extends RebalanceConstraints {
    def obeysRackConstraint(partition: TopicAndPartition, brokerFrom: Int, brokerTo: Int, replicationFactors: Map[String, Int]): Boolean = {
      val minRacksSpanned = Math.min(replicationFactors.get(partition.topic).get, rackCount)

      //get replicas for partition, replacing brokerFrom with brokerTo
      var proposedReplicas: Seq[Int] = partitions.get(partition).get
      val index: Int = proposedReplicas.indexOf(brokerFrom)
      proposedReplicas = proposedReplicas.patch(index, Seq(brokerTo), 1)

      //find how many racks are now spanned
      val racksSpanned = proposedReplicas.map(bk(_)).map(_.rack).distinct.size

      racksSpanned >= minRacksSpanned
    }

    def obeysPartitionConstraint(replica: TopicAndPartition, brokerMovingTo: Int): Boolean = {
      !replicasFor(brokerMovingTo).map(_.partition).contains(replica)
    }
  }

  /**
    * Find the least loaded brokers, but push those on the supplied racks to the bottom of the list.
    *
    * Then least loaded would be 103, 102, 101, 100 (based on replica count with least loaded last)
    * but rack1 (100, 101) should drop in priority so we should get:
    *
    * The least loaded broker will be returned first
    *
    */
  def leastLoadedBrokersPreferringOtherRacks(racks: Seq[String]): Iterable[Int] = {
    downrank(brokersOn(racks), leastLoadedBrokerIds()).reverse
  }

  def racksFor(p: TopicAndPartition): Seq[String] = {
    allBrokers.filter(broker =>
      partitions.get(p).get
        .contains(broker.id)
    ).map(_.rack.get)
  }

  private def leastLoadedBrokerIds(rack: String): Seq[BrokerMetadata] = {
    brokersToReplicas.map(_._1).reverse
      .filter(broker => broker.rack.get == rack)
  }

  private def brokersOn(racks: Seq[String]): Seq[Int] = {
    allBrokers.filter(broker => racks.contains(broker.rack.get)).map(_.id)
  }

  private def leadersOn(rack: String): Seq[TopicAndPartition] = {
    brokersToLeaders
      .filter(_._1.rack.get == rack)
      .map(_._2)
      .flatMap(x => x)
  }

  private def leadersOn(broker: BrokerMetadata): Seq[TopicAndPartition] = {
    brokersToLeaders //TODO should probably be a map lookup
      .filter(_._1 == broker)
      .map(_._2).last.toSeq
  }

  private def weightedReplicasFor(rack: String): Seq[Replica] = {
    //TODO implement weighting later - for now just return replicas in rack in any order
    //TODO2 we need to interleave these results by broker see MovesOptimisedRebalancePolicyTest.providesPotentiallyUnexpectedResult
    brokersToReplicas.filter(_._1.rack.get == rack).sortBy(_._2.size).map(_._2).flatten
  }

  private def weightedReplicasFor(broker: BrokerMetadata): Seq[Replica] = {
    //TODO implement weighting later - for now just return replicas in rack in any order
    //TODO2 we need to interleave these results by broker see MovesOptimisedRebalancePolicyTest.providesPotentiallyUnexpectedResult to
    brokersToReplicas.filter(_._1 == broker).sortBy(_._2.size).map(_._2).flatten
  }



}

