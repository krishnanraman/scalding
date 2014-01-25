package com.twitter.scalding

import cascading.stats.{ CascadeStats, CascadingStats }
import cascading.flow.FlowProcess
import cascading.stats.FlowStats

import scala.collection.JavaConverters._

import org.slf4j.{Logger, LoggerFactory}

/**
 * Wrapper around a FlowProcess useful, for e.g. incrementing counters.
 */

trait StatsEnabled {
  // This is the group that we assign all custom counters to
  val ScaldingGroup = "Scalding Custom"

  def setFlowProcess(fp: FlowProcess[_])
  def setFlowStats(fs: FlowStats)
  def setCascadeStats(cs: CascadeStats)
  def getCounterValue(counter: String, group: String = ScaldingGroup): Option[Long]
  def getAllCustomCounters: Map[String, Long]
  def incrementCounter(name: String, amount: Long = 1L, group: String = ScaldingGroup): Unit
  // Use this if a map or reduce phase takes a while before emitting tuples.
  def keepAlive: Unit
}

class Stats extends StatsEnabled {
  @transient private lazy val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private var flowProcess: FlowProcess[_] = null
  private var flowStats: Option[FlowStats] = None
  private var cascadeStats: Option[CascadeStats] = None
  def setFlowProcess(fp: FlowProcess[_]) = flowProcess = fp
  def setFlowStats(fs: FlowStats) = flowStats = Some(fs)
  def setCascadeStats(cs: CascadeStats) = cascadeStats = Some(cs)

  // When getting a counter value, cascadeStats takes precedence (if set) and
  // flowStats is used after that. Returns None if neither is defined.
  def getCounterValue(counter: String, group: String = ScaldingGroup): Option[Long] =
    statsClass.map { _.getCounterValue(ScaldingGroup, counter) }

  // Returns a map of all custom counter names and their counts.
  def getAllCustomCounters: Map[String, Long] = {
    val counts = for {
      s <- statsClass.toSeq
      counter <- s.getCountersFor(ScaldingGroup).asScala
      value <- getCounterValue(counter).toSeq
    } yield (counter, value)
    counts.toMap
  }

  def incrementCounter(name: String, amount: Long = 1L, group: String = ScaldingGroup): Unit =
    // We do this in a tight loop, and the var is private, so just be really careful and do null check
    if(null != flowProcess) {
      flowProcess.increment(group, name, amount)
    }
    else {
      logger.warn(
        "no flowProcess while incrementing(name = %s, amount = %d, group = %s)"
          .format(name, amount, group)
      )
    }

  // We do this in a tight loop
  // the var is private, and we are normally stored in a thread local.
  def keepAlive: Unit =
    if(null != flowProcess) {
      flowProcess.keepAlive
    }
    else {
      logger.warn("no flowProcess while calling keepAlive")
    }

  private[this] def statsClass: Option[CascadingStats] = (cascadeStats, flowStats) match {
    case (Some(_), _) => cascadeStats
    case (_, Some(_)) => flowStats
    case _ => None
  }
}

object Stats extends StatsEnabled with java.io.Serializable {
  private lazy val tlStats = new ThreadLocal[Stats] {
    override def initialValue() = {
      println("Initializing custom counter")
      new Stats
    }
  }
  private def loc = tlStats.get

  def setFlowProcess(fp: FlowProcess[_]) = loc.setFlowProcess(fp)
  def setFlowStats(fs: FlowStats) = loc.setFlowStats(fs)
  def setCascadeStats(cs: CascadeStats) = loc.setCascadeStats(cs)
  def getCounterValue(counter: String, group: String) = loc.getCounterValue(counter, group)
  def getAllCustomCounters = loc.getAllCustomCounters
  def incrementCounter(name: String, amount: Long, group: String) =
                      loc.incrementCounter(name, amount, group)
  def keepAlive = loc.keepAlive
}
