package kafka.poc

import kafka.admin.BrokerMetadata
import kafka.common.TopicAndPartition

import scala.collection.{Iterable, Map, Seq}

trait TopologyFactory {

  //TODO Consolidate
  def createBrokersToReplicas(brokers: Seq[BrokerMetadata], relevantBrokers: Seq[BrokerMetadata], partitions: Map[TopicAndPartition, Seq[Int]]): Seq[(BrokerMetadata, Seq[Replica])] = {

    def bk(id: Int): BrokerMetadata = brokers.filter(_.id == id).last

    val existing = partitions
      .map { case (tp, replicas) => (tp, replicas.map(new Replica(tp.topic, tp.partition, _))) } //enrich replica object
      .values
      .flatMap(replica => replica) //list of all replicas
      .groupBy(replica => replica.broker) //group by broker to create: broker->[Replica]
      .toSeq
      .sortBy(_._2.size) //sort by highest replica count
      .map { x => (bk(x._1), x._2.toSeq) } //turn broker id into BrokerMetadata

    val emptyBrokers = brokers.filterNot(existing.map(_._1).toSet)
      .map(x => (x, Seq.empty[Replica]))

    emptyBrokers ++ existing
  }

  //TODO Consolidate
  def createBrokersToLeaders(brokers: Seq[BrokerMetadata], relevantBrokers: Seq[BrokerMetadata], partitions: Map[TopicAndPartition, Seq[Int]]): Seq[(BrokerMetadata, Iterable[TopicAndPartition])] = {

    def bk(id: Int): BrokerMetadata = {
      brokers.filter(_.id == id).last
    }

    val existing = partitions
      .filter(_._2.size > 0)
      .map { case (tp, replicas) => (tp, (tp, bk(replicas(0)))) }.values //map to leaders
      .groupBy(_._2) //group by brokers to create: Broker -> [TopicAndPartition]
      .toSeq
      .sortBy(_._2.size)
      .map { case (x, y) => (x, y.map(x => x._1)) }

    val emptyBrokers = brokers.filterNot(existing.map(_._1).toSet)
      .map(x => x -> Iterable.empty[TopicAndPartition])

    emptyBrokers ++ existing
  }

  //TODO Consolidate
  def createBrokersToNonLeaders(brokers: Seq[BrokerMetadata], relevantBrokers: Seq[BrokerMetadata], partitions: Map[TopicAndPartition, Seq[Int]]): Seq[(BrokerMetadata, Seq[Replica])] = {

    def bk(id: Int): BrokerMetadata = brokers.filter(_.id == id).last

    val existing = partitions
      .map { case (tp, replicas) => (tp, replicas.drop(1).map(new Replica(tp.topic, tp.partition, _))) } //enrich replica object
      .values
      .flatMap(replica => replica) //list of all replicas
      .groupBy(replica => replica.broker) //group by broker to create: broker->[Replica]
      .toSeq
      .sortBy(_._2.size) //sort by highest replica count
      .map { x => (bk(x._1), x._2.toSeq) } //turn broker id into BrokerMetadata

    val emptyBrokers = brokers.filterNot(existing.map(_._1).toSet)
      .map(x => (x, Seq.empty[Replica]))

    emptyBrokers ++ existing
  }
}