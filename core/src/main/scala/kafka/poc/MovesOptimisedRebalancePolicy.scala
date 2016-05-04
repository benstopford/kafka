package kafka.poc

import kafka.admin.BrokerMetadata
import kafka.common.TopicAndPartition

import scala.collection._


class MovesOptimisedRebalancePolicy extends RabalancePolicy {

  override def rebalancePartitions(brokers: Seq[BrokerMetadata], replicasForPartitions: Map[TopicAndPartition, Seq[Int]], replicationFactors: Map[String, Int]): Map[TopicAndPartition, Seq[Int]] = {
    val partitions = collection.mutable.Map(replicasForPartitions.toSeq: _*) //todo deep copy?
    val cluster = new ClusterTopologyView(brokers, partitions)
    println("\nBrokers: " + brokers.map { b => "\n" + b })
    print(partitions, cluster)

    //1. Ensure no under-replicated partitions
    fullyReplicated(partitions, cluster, replicationFactors)

    //2. Optimise Racks
    replicaFairness(partitions, cluster.constraints, replicationFactors, cluster.byRack)
    leaderFairness(partitions, cluster.byRack)

    //3. Optimise brokers on each byRack
    replicaFairness(partitions, cluster.constraints, replicationFactors, cluster.byBroker)
    leaderFairness(partitions, cluster.byBroker)

    println("\nResult is:")
    print(partitions, cluster)
    partitions
  }

  /**
    * This method O(#under-replicated-partitions * #parititions) as we reevaluate the least loaded brokers for each under-replicated one we find
    * (could be optimised further but this seems a reasonable balance between simplicity and cost).
    */
  def fullyReplicated(partitionsMap: mutable.Map[TopicAndPartition, Seq[Int]], cluster: ClusterTopologyView, replicationFactors: Map[String, Int]): Unit = {
    for (partition <- partitionsMap.keys) {
      def replicationFactor = replicationFactors.get(partition.topic).get
      def replicasForP = partitionsMap.get(partition).get

      while (replicasForP.size < replicationFactor) {
        val leastLoadedBrokers = cluster.leastLoadedBrokersPreferringOtherRacks(cluster.racksFor(partition))
        val leastLoadedValidBrokers = leastLoadedBrokers.filterNot(replicasForP.toSet).iterator

        val leastLoaded = leastLoadedValidBrokers.next
        partitionsMap.put(partition, replicasForP :+ leastLoaded)
        println(s"Additional replica was created on broker [$leastLoaded] for under-replicated partition [$partition].")
      }
    }
  }

  def replicaFairness(partitionsMap: mutable.Map[TopicAndPartition, scala.Seq[Int]], constraints: RebalanceConstraints, replicationFactors: Map[String, Int], view: ClusterView ) = {
    for (replicaFrom <- view.aboveParReplicas) {
      var moved = false
      for (brokerTo <- view.belowParBrokers) {
        if (constraints.obeysPartitionConstraint(replicaFrom.partition, brokerTo.id) && moved == false) {
          if (constraints.obeysRackConstraint(replicaFrom.partition, replicaFrom.broker, brokerTo.id, replicationFactors)) {
            move(replicaFrom.partition, replicaFrom.broker, brokerTo.id, partitionsMap)
            moved = true
          }
        }
      }
    }
  }

  def leaderFairness(partitions: mutable.Map[TopicAndPartition, scala.Seq[Int]], view: ClusterView ): Unit = {
    for (aboveParBroker <- view.aboveParLeaders) {//*1
      //check to see if the partition has a non-leader replica on below par racks
      for (replica <- partitions.get(aboveParBroker).get.drop(1)) {
        if (view.brokersWithBelowParLeaders.contains(replica)) {
          //if so, switch leadership
          makeLeader(aboveParBroker, replica, partitions)
        }
      }
    }
    //*1 do we need: if (cluster.leaderFairness.aboveParRacks().contains(aboveParRack)) {
  }

  def makeLeader(tp: TopicAndPartition, toPromote: Int, partitionsMap: collection.mutable.Map[TopicAndPartition, Seq[Int]]): Unit = {
    var replicas = partitionsMap.get(tp).get
    var currentLead = replicas(0)
    //remove old
    replicas = replicas.filter(_ != toPromote)
    //push first
    replicas = Seq(toPromote) ++ replicas
    partitionsMap.put(tp, replicas)
    println(s"Leadership moved brokers: [$currentLead -> $toPromote] for partition $tp")
  }

  def move(tp: TopicAndPartition, from: Int, to: Int, partitionsMap: collection.mutable.Map[TopicAndPartition, Seq[Int]]): Unit = {
    var replicas = partitionsMap.get(tp).get
    //remove old
    replicas = replicas.filter(_ != from)
    //add new
    replicas = replicas :+ to
    partitionsMap.put(tp, replicas)
    println(s"Partition $tp was moved from broker [$from] to [$to]")
  }

  def print(partitionsMap: mutable.Map[TopicAndPartition, scala.Seq[Int]], cluster: ClusterTopologyView): Unit = {
    println("\nPartitions to brokers: " + partitionsMap.map { case (k, v) => "\n" + k + " => " + v }.toSeq.sorted)
    println("\nBrokers to partitions: " + cluster.brokersToReplicas.map { x => "\n" + x._1.id + " : " + x._2.map("p" + _.partitionId) } + "\n")
  }
}
