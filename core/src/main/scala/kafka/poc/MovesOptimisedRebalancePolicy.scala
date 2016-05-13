package kafka.poc

import kafka.admin.BrokerMetadata
import kafka.common.TopicAndPartition
import kafka.poc.constraints.Constraints
import kafka.poc.view.BrokerFairView
import kafka.poc.topology.{Replica, TopologyHelper, TopologyFactory}
import kafka.poc.view.{BrokerFairView, ClusterView, RackFairView}

import scala.collection._

/**
  * A policy which takes a cluster topology, as a set of partitions->brokerIds, and rebalances it using
  * a supplied notion of fairness.
  *
  * The policy is incremental, meaning it will move the minimum number of replicas required to achieve
  * replica and leader fairness across the cluster.
  *
  * The algorithm is strictly rack aware with respect to replica placement. This means that if racks are
  * not assigned brokers equally the number of replicas on each broker may be skewed.
  *
  * However leaders will be balanced equally amoungst brokers, regardless of what rack they are on.
  *
  */
class MovesOptimisedRebalancePolicy extends RabalancePolicy with TopologyHelper with TopologyFactory {

  override def rebalancePartitions(brokers: Seq[BrokerMetadata], replicasForPartitions: Map[TopicAndPartition, Seq[Int]], replicationFactors: Map[String, Int]): Map[TopicAndPartition, Seq[Int]] = {
    val partitions = collection.mutable.Map(replicasForPartitions.toSeq: _*) //todo deep copy?
    val constraints: Constraints = new Constraints(brokers, partitions)

    //1. Ensure no under-replicated partitions
    fullyReplicated(partitions, constraints, replicationFactors, brokers)

    //2. Create replica fairness across racks
    val rackView = new RackFairView(brokers, partitions)
    replicaFairness(partitions, replicationFactors, rackView)

    //3. Create replica fairness for brokers, on each rack separately
    for (rack <- racks(brokers)) {
      def brokerView = new BrokerFairView(brokers, partitions, rack)
      replicaFairness(partitions, replicationFactors, brokerView)
    }

    //4. Create leader fairness for brokers, applied cluster-wide
    val brokerView = new BrokerFairView(brokers, partitions, null)
    leaderFairness(partitions, brokerView)

    println("The result is:")
    print(partitions, brokers)
    partitions
  }

  /**
    * Create new replicas for any under-replicated partitions on a least loaded broker. If a partition cannot
    * be fully replicated, due to there not being a valid broker available, the algorithm will progress regardless
    * but outputting a warning.
    *
    * @param partitions  Map of partitions to brokers which will be mutated
    * @param constraints Validation of partition and rack constraints
    * @param rfs         Replication factors for all topics
    * @param allBrokers  List of all brokers, including those without replicas
    * @return
    */
  def fullyReplicated(partitions: mutable.Map[TopicAndPartition, Seq[Int]], constraints: Constraints, rfs: Map[String, Int], allBrokers: Seq[BrokerMetadata]): Map[TopicAndPartition, Seq[Int]] = {
    val brokersToReplicas = createBrokersToReplicas(allBrokers, allBrokers, partitions)

    for (partition <- partitions.keys) {
      val replicationFactor = rfs.get(partition.topic).get
      def racks = racksFor(partition, allBrokers, partitions)
      def replicas = partitions.get(partition).get

      def createReplicaOnFirstValid(leastLoadedBrokers: Iterable[Int]): Unit = {
        for (destinationBroker <- leastLoadedBrokers) {
          if (constraints.obeysPartitionConstraint(partition, destinationBroker)
            && constraints.obeysRackConstraint(partition, -1, destinationBroker, rfs)) {
            partitions.put(partition, replicas :+ destinationBroker)
            return
          }
        }
        println(s"WARNING: Could not create replica due to either rack or partition constraints. Thus this partition will remain under-replicated")
      }

      (0 until replicationFactor - replicas.size) foreach { _ =>
        val leastLoadedBrokers = leastLoadedBrokersPreferringOtherRacks(brokersToReplicas, allBrokers, racks)
        createReplicaOnFirstValid(leastLoadedBrokers)
      }
    }
    partitions
  }

  /**
    * Move replicas on above-par brokers/racks to below-par brokers/racks if they obey partition/rack constraints.
    *
    * @param partitions         Map of partitions to brokers which will be mutated
    * @param replicationFactors Replication factors for all topics
    * @param clusterView        View of the cluster which incorporates fairness
    */
  def replicaFairness(partitions: mutable.Map[TopicAndPartition, Seq[Int]], replicationFactors: Map[String, Int], clusterView: ClusterView): Unit = {
    var view = clusterView

    def moveToBelowParBroker(abovePar: Replica): Unit = {
      for (belowPar <- view.brokersWithBelowParReplicaCount) {
        val obeysPartition = view.constraints.obeysPartitionConstraint(abovePar.partition, belowPar.id)
        val obeysRack = view.constraints.obeysRackConstraint(abovePar.partition, abovePar.broker, belowPar.id, replicationFactors)

        if (obeysRack && obeysPartition) {
          move(abovePar.partition, abovePar.broker, belowPar.id, partitions)
          view = view.refresh(partitions)
          return
        }
      }
    }

    for (abovePar <- view.replicasOnAboveParBrokers)
      moveToBelowParBroker(abovePar)
  }

  /**
    * Move leadership to from above-par to below-par brokers. If a valid follower replica exists for this partition,
    * leadership is simply switched (without a resulting data movement). If this cannot be achieved a replica from a
    * different partition, on a above-par broker, will be selected, and the two replicas will be swapped (i.e. two
    * way data movement) allowing leadership to be moved to a below-par broker.
    *
    * @param partitions Map of partitions to brokers which will be mutated
    * @param clusterView View of the cluster which incorporates fairness
    */
  def leaderFairness(partitions: mutable.Map[TopicAndPartition, scala.Seq[Int]], clusterView: ClusterView): Unit = {
    var view = clusterView

    val abParParts = view.leadersOnAboveParBrokers
    for (aboveParLeaderPartition <- abParParts) {
      var moved = false

      //Attempt to switch leadership within partitions to achieve fairness (i.e. no data movement)
      for (aboveParFollowerBrokerId <- partitions.get(aboveParLeaderPartition).get.drop(1)) {
        val brokersWithBelowParLeaders = view.brokersWithBelowParLeaderCount
        if (brokersWithBelowParLeaders.map(_.id).contains(aboveParFollowerBrokerId)) {
          //if so, switch leadership
          makeLeader(aboveParLeaderPartition, aboveParFollowerBrokerId, partitions)
          view = view.refresh(partitions)
          moved = true
        }
      }

      //If that didn't succeed, pick a replica from another partition, which is on a below par broker, and physically swap them around.
      if (!moved) {
        val aboveParLeaderBroker = partitions.get(aboveParLeaderPartition).get(0)
        for (broker <- view.brokersWithBelowParLeaderCount) {
          val followerReplicasOnBelowParBrokers = view.nonLeadReplicasFor(broker)
          for (belowParFollowerReplica <- followerReplicasOnBelowParBrokers) {
            val obeysPartitionOut = view.constraints.obeysPartitionConstraint(aboveParLeaderPartition, belowParFollowerReplica.broker)
            val obeysPartitionBack = view.constraints.obeysPartitionConstraint(belowParFollowerReplica.partition, aboveParLeaderBroker)

            if (!moved && obeysPartitionOut && obeysPartitionBack) {
              move(aboveParLeaderPartition, aboveParLeaderBroker, belowParFollowerReplica.broker, partitions)
              move(belowParFollowerReplica.partition, belowParFollowerReplica.broker, aboveParLeaderBroker, partitions)
              view = view.refresh(partitions)
              moved = true
            }
          }
        }
      }
    }
  }

  def makeLeader(tp: TopicAndPartition, toPromote: Int, partitionsMap: collection.mutable.Map[TopicAndPartition, Seq[Int]]): Unit = {
    var replicas = partitionsMap.get(tp).get
    var currentLead = replicas(0)

    if (toPromote != currentLead) {
      replicas = replicas.filter(_ != toPromote)
      replicas = Seq(toPromote) ++ replicas
      partitionsMap.put(tp, replicas)
      println(s"Leadership moved brokers: [$currentLead -> $toPromote] for partition $tp:${partitionsMap.get(tp).get}")
    }
    else println(s"Leadership change was not made as $toPromote was already the leader for partition $tp - see: ${partitionsMap.get(tp).get}")
  }

  def move(tp: TopicAndPartition, from: Int, to: Int, partitionsMap: collection.mutable.Map[TopicAndPartition, Seq[Int]]): Unit = {
    def replaceFirst[A](a: Seq[A], repl: A, replwith: A): List[A] = a match {
      case Nil => Nil
      case head :: tail => if (head == repl) replwith :: tail else head :: replaceFirst(tail, repl, replwith)
    }

    if (to == from)
      println(s"Movement was not made as $to was already the broker $from")
    else {
      val replicas = replaceFirst(partitionsMap.get(tp).get, from, to)
      partitionsMap.put(tp, replicas)
    }
  }

  def print(partitionsMap: mutable.Map[TopicAndPartition, scala.Seq[Int]], brokers: Seq[BrokerMetadata]): Unit = {
    val brokersToReplicas = createBrokersToReplicas(brokers, brokers, partitionsMap)
    val brokersToLeaders = createBrokersToLeaders(brokers, brokers, partitionsMap)
    println("\nPartitions to brokers: " + partitionsMap.map { case (k, v) => "\n" + k + " => " + v }.toSeq.sorted)
    println("\nBrokers to replicas: " + brokersToReplicas.map { x => "\n" + x._1.id + " : " + x._2.map("p" + _.partitionId) } + "\n")
    println("\nBrokers to leaders: " + brokersToLeaders.map { x => "\n" + x._1.id + " - size:" + x._2.size } + "\n")
    println("\nRacks to replica Counts " + getRackReplicaCounts(brokersToReplicas))
    println("\nRacks to leader Counts " + getRackLeaderCounts(brokersToLeaders))
    println("\nBroker to replica Counts " + getBrokerReplicaCounts(brokersToReplicas).map { case (k, v) => (k.id, v) })
    println("\nBroker to leader Counts " + getBrokerLeaderCounts(brokersToLeaders))
  }

  def print(partitionsMap: mutable.Map[TopicAndPartition, scala.Seq[Int]]): Unit = {
    println("\nPartitions to brokers: " + partitionsMap.map { case (k, v) => "\n" + k + " => " + v }.toSeq.sorted)
  }
}