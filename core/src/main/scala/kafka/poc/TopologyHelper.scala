package kafka.poc

import kafka.admin.BrokerMetadata
import kafka.common.TopicAndPartition

import scala.collection.immutable.ListMap
import scala.collection.{mutable, Map, Iterable, Seq}
import scala.util.Random

trait TopologyHelper {

  def leastLoadedBrokerIds(btr: Seq[(BrokerMetadata, Seq[Replica])]): Seq[Int] = btr.map(_._1.id).reverse

  def replicaExists(btr: Seq[(BrokerMetadata, Seq[Replica])], replica: Any, rack: String): Boolean = btr.filter(_._1.rack.get == rack).map(_._2).size > 0

  /**
    * Find the least loaded brokers, but push those on the supplied racks to the bottom of the list.
    *
    * Then least loaded would be 103, 102, 101, 100 (based on replica count with least loaded last)
    * but rack1 (100, 101) should drop in priority so we should get:
    *
    * The least loaded broker will be returned first
    *
    */
  def leastLoadedBrokersPreferringOtherRacks(btr: Seq[(BrokerMetadata, Seq[Replica])],brokers: Seq[BrokerMetadata], racks: Seq[String]): Iterable[Int] = {
    downrank(brokersOn(racks, brokers).map(_.id), leastLoadedBrokerIds(btr)).reverse
  }

  private def downrank(toDownrank: Seq[Int], all: Seq[Int]): Seq[Int] = {
    val notDownranked = all.filterNot(toDownrank.toSet)
    val downranked = all.filter(toDownrank.toSet)

    downranked ++ notDownranked
  }

  def racksFor(p: TopicAndPartition, brokers: Seq[BrokerMetadata], partitions: Map[TopicAndPartition, Seq[Int]]): Seq[String] = {
    brokers.filter(broker =>
      partitions.get(p).get
        .contains(broker.id)
    ).map(_.rack.get)
  }

  def rackCount(brokers: Seq[BrokerMetadata]): Int = racks(brokers).size

  def racks(brokers: Seq[BrokerMetadata]): Seq[String] = brokers.map(_.rack.get).distinct

  def brokersOn(racks: Seq[String], brokers: Seq[BrokerMetadata]): Seq[BrokerMetadata] =     brokers.filter(broker => racks.contains(broker.rack.get))

  def filter(rack: String, brokers: Seq[BrokerMetadata], partitions: Map[TopicAndPartition, Seq[Int]]): Map[TopicAndPartition, Seq[Int]] = {
    def bk(id: Int): BrokerMetadata = brokers.filter(_.id == id).last

    partitions.map { case (p, replicas) => (p, replicas.filter(bk(_).rack.get == rack)) }
      .filter { case (p, replicas) => replicas.size > 0 }
  }

  def leadersOn(rack: String, brokersToLeaders: Seq[(BrokerMetadata, Iterable[TopicAndPartition])]): Seq[TopicAndPartition] = {
    brokersToLeaders
      .filter(_._1.rack.get == rack)
      .map(_._2)
      .flatMap(x => x)
  }

  def weightedReplicasFor(rack: String, brokersToReplicas: Seq[(BrokerMetadata, Seq[Replica])]): Seq[Replica] = {
    //TODO implement weighting later - for now just return replicas in rack in any order
    //TODO2 we need to interleave these results by broker see MovesOptimisedRebalancePolicyTest.providesPotentiallyUnexpectedResult
      brokersToReplicas.filter(_._1.rack.get == rack).sortBy(_._2.size).map(_._2).flatten
  }

  def leastLoadedBrokerIds(rack: String, brokersToReplicas: Seq[(BrokerMetadata, Seq[Replica])]): Seq[BrokerMetadata] = {
    brokersToReplicas.map(_._1).reverse
      .filter(broker => broker.rack.get == rack)
  }

  def weightedReplicasFor(broker: BrokerMetadata, brokersToReplicas: Seq[(BrokerMetadata, Seq[Replica])]): Seq[Replica] = {
    //TODO implement weighting later - for now just return replicas in rack in any order
    //TODO2 we need to interleave these results by broker see MovesOptimisedRebalancePolicyTest.providesPotentiallyUnexpectedResult to
      brokersToReplicas.filter(_._1 == broker).sortBy(_._2.size).map(_._2).flatten
  }

  def leadersOn(broker: BrokerMetadata, brokersToLeaders: Seq[(BrokerMetadata, Iterable[TopicAndPartition])]): Seq[TopicAndPartition] = {
    brokersToLeaders //TODO should probably be a map lookup
      .filter(_._1 == broker)
      .map(_._2).last.toSeq
  }

  //duplicate
  def getRackLeaderCounts(brokersToLeaders: Seq[(BrokerMetadata, Iterable[TopicAndPartition])]): Map[String, Int] = {
    brokersToLeaders
      .map { case (x, y) => (x, y.size) }
      .groupBy(_._1.rack.get)
      .mapValues(_.map(_._2).sum)
  }

  //duplicate
  def getBrokerLeaderCounts(brokersToLeaders: Seq[(BrokerMetadata, Iterable[TopicAndPartition])]) = ListMap(
    brokersToLeaders
      .map { case (x, y) => (x, y.size) }
      .sortBy(_._2)
      : _*
  )

  //duplicate
  def getBrokerReplicaCounts(brokersToReplicas: Seq[(BrokerMetadata, Seq[Replica])]) = ListMap(
    brokersToReplicas
      .map { case (x, y) => (x, y.size) }
      .sortBy(_._2)
      : _*
  )

  //duplicate
  def getRackReplicaCounts(brokersToReplicas: Seq[(BrokerMetadata, Seq[Replica])]) = ListMap(
    brokersToReplicas
      .map { case (x, y) => (x, y.size) }
      .groupBy(_._1.rack.get)
      .mapValues(_.map(_._2).sum)
      .toSeq
      .sortBy(_._2)
      : _*
  )
}