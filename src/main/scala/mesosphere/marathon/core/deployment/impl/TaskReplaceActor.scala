package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor._
import akka.event.EventStream
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.ReadinessCheckUpdate
import mesosphere.marathon.core.deployment.impl.ReadinessBehavior.{ReadinessCheckStreamDone, ReadinessCheckSubscriptionKey}
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.{ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec}

import scala.async.Async.{async, await}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

class TaskReplaceActor(
    val deploymentManagerActor: ActorRef,
    val status: DeploymentStatus,
    val killService: KillService,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    promise: Promise[Unit]) extends Actor with Stash with StrictLogging {
  import TaskReplaceActor._

  val pathId: PathId = runSpec.id

  // All running instances of this app
  val instances: mutable.Map[Instance.Id, Instance] = instanceTracker.specInstancesSync(runSpec.id).map { i => i.instanceId -> i }(collection.breakOut)
  val instancesHealth: mutable.Map[Instance.Id, Boolean] = instances.collect {
    case (id, instance) => id -> instance.state.healthy.getOrElse(false)
  }
  val instancesReady: mutable.Map[Instance.Id, Boolean] = mutable.Map.empty

  // The ignition strategy for this run specification
  private[this] val ignitionStrategy = computeRestartStrategy(runSpec, instances.size)

  @SuppressWarnings(Array("all")) // async/await
  override def preStart(): Unit = {
    super.preStart()
    // subscribe to all needed events
    eventBus.subscribe(self, classOf[InstanceChanged])
    eventBus.subscribe(self, classOf[InstanceHealthChanged])

    // kill old instances to free some capacity
    logger.info("Sending immediate kill")
    self ! KillImmediately(ignitionStrategy.nrToKillImmediately)

    // reset the launch queue delay
    logger.info("Resetting the backoff delay before restarting the runSpec")
    launchQueue.resetDelay(runSpec)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = killing

  // Commands
  case object Check
  case object KillNext
  case class KillImmediately(oldInstances: Int)
  case class Killed(id: Seq[Instance.Id])
  case object ScheduleReadiness
  case object LaunchNext
  case class Scheduled(instances: Seq[Instance])

  /* Phases
  We cycle through the following update phases:

  1. `updating` process instance updates and apply them to our internal state.
  2. `checking` Check if all old instances are terminal and all new instances are running, ready and healthy.
  3. `killing` Kill the next old instance, ie set the goal and update our internal state. We are ahead of what has been persisted to ZooKeeper.
  4. `launching` Scheduler one readiness check for a healthy new instance and launch a new instance if required.
  */

  def updating: Receive = {
    case InstanceChanged(id, _, _, _, instance) =>
      logPrefixedInfo("updating")(s"Received update for $instance")
      instances += id -> instance

      context.become(checking)
      self ! Check

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case InstanceHealthChanged(id, _, `pathId`, healthy) =>
      logPrefixedInfo("updating")(s"Received health update for $id: $healthy")
      // TODO(karsten): The original logic check the health only once. It was a rather `wasHealthyOnce` check.
      instancesHealth += id -> healthy.getOrElse(false)

      context.become(checking)
      self ! Check

    // TODO(karsten): It would be easier just to receive instance changed updates.
    case result: ReadinessCheckResult =>
      logPrefixedInfo("updating")(s"Received readiness check update for ${result.taskId.instanceId} with ready: ${result.ready}")
      deploymentManagerActor ! ReadinessCheckUpdate(status.plan.id, result)
      //TODO(MV): this code assumes only one readiness check per run spec (validation rules enforce this)
      if (result.ready) {
        logger.info(s"Task ${result.taskId} now ready for app ${runSpec.id.toString}")
        instancesReady += result.taskId.instanceId -> true
        unsubscripeReadinessCheck(result)
      }

      context.become(checking)
      self ! Check

    // TODO(karsten): Should we re-initiate the health check?
    case ReadinessCheckStreamDone(subscriptionName, maybeFailure) =>
      maybeFailure.foreach { ex =>
        // We should not ever get here
        logger.error(s"Received an unexpected failure for readiness stream $subscriptionName", ex)
      }
      logPrefixedInfo("updating")(s"Readiness check stream $subscriptionName is done")
      subscriptions -= subscriptionName

      context.become(checking)
      self ! Check
  }

  // Check if we are done.
  def checking: Receive = {
    case Check =>
      val readableInstances = instances.values.map { instance =>
        s"Instance(id=${instance.instanceId}, version=${instance.runSpecVersion}, goal=${instance.state.goal}, condition=${instance.state.condition})"
      }.mkString(",")
      logPrefixedInfo("checking")(s"Checking if we are done for $readableInstances")
      // Are all old instances terminal?
      val oldTerminal = instances.valuesIterator.filter(_.runSpecVersion < runSpec.version).forall { instance =>
        considerTerminal(instance.state.condition) && instance.state.goal != Goal.Running
      }

      // Are all new instances running, ready and healthy?
      val newActive = instances.valuesIterator.count { instance =>
        val healthy = if (hasHealthChecks) instancesHealth.getOrElse(instance.instanceId, false) else true
        val ready = if (hasReadinessChecks) instancesReady.getOrElse(instance.instanceId, false) else true
        instance.runSpecVersion == runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running && healthy && ready
      }

      val newStaged = instances.valuesIterator.count { instance =>
        instance.runSpecVersion == runSpec.version && !instance.state.condition.isActive && instance.state.goal == Goal.Running
      }

      if (oldTerminal && newActive == runSpec.instances) {
        logPrefixedInfo("checking")(s"All new instances for $pathId are ready and all old instances have been killed")
        promise.trySuccess(())
        context.stop(self)
      } else {
        logPrefixedInfo("checking")(s"Not done yet: old: $oldTerminal, new active: $newActive, new staged: $newStaged")
        context.become(killing)
        self ! KillNext
      }

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  // Kill next old instance
  def killing: Receive = {
    case KillImmediately(oldInstances) =>
      logPrefixedInfo("killing")(s"Killing $oldInstances immediately.")
      instances.valuesIterator
        .filter { instance =>
          instance.runSpecVersion < runSpec.version && instance.state.goal == Goal.Running
        }
        .take(oldInstances)
        .foldLeft(Future.successful(Seq.empty[Instance.Id])) { (acc, nextDoomed) =>
          async {
            val current = await(acc)
            await(killNextOldInstance(nextDoomed))
            current :+ nextDoomed.instanceId
          }
        }.map(ids => Killed(ids)).pipeTo(self)

    case KillNext =>
      // TODO(karsten): Set goal of out of band terminal instances to stopped/decommissioned.
      logPrefixedInfo("killing")("Picking next old instance.")
      // Pick first active old instance that has goal running
      instances.valuesIterator.find { instance =>
        instance.runSpecVersion < runSpec.version && instance.state.goal == Goal.Running
      } match {
        case Some(doomed) => killNextOldInstance(doomed).map(_ => Killed(Seq(doomed.instanceId))).pipeTo(self)
        case None =>
          logPrefixedInfo("killing")("No next instance to kill.")
          self ! Killed(Seq.empty)
      }

    case Killed(killedIds) =>
      logPrefixedInfo("killing")(s"Marking $killedIds as stopped.")
      // TODO(karsten): We may want to wait for `InstanceChanged(instanceId, ..., Goal.Stopped | Goal.Decommissioned)`.
      // We mark the instance as doomed so that we won't select it in the next run.
      killedIds.foreach { instanceId =>
        val killedInstance = instances(instanceId)
        val updatedState = killedInstance.state.copy(goal = Goal.Stopped)
        instances += instanceId -> killedInstance.copy(state = updatedState)
      }

      context.become(launching)
      self ! ScheduleReadiness

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  // Launch next new instance
  def launching: Receive = {
    case ScheduleReadiness =>
      // Schedule readiness check for new healthy instance that has no scheduled check yet.
      if (hasReadinessChecks) {
        logPrefixedInfo("launching")("Scheduling readiness check.")
        instances.valuesIterator.find { instance =>
          val noReadinessCheckScheduled = !instancesReady.contains(instance.instanceId)
          instance.runSpecVersion == runSpec.version && instance.state.condition.isActive && instance.state.goal == Goal.Running && noReadinessCheckScheduled
        } foreach { instance =>
          initiateReadinessCheck(instance)

          // Mark new instance as not ready
          instancesReady += instance.instanceId -> false
        }
      } else {
        logPrefixedInfo("launching")("No need to schedule readiness check.")
      }
      self ! LaunchNext

    case LaunchNext =>
      logPrefixedInfo("launching")("Launching next instance")
      //      val oldActiveInstances = instances.valuesIterator.count { instance =>
      //        instance.runSpecVersion < runSpec.version && !considerTerminal(instance.state.condition) && instance.state.goal == Goal.Running
      //      }
      val oldTerminalInstances = instances.valuesIterator.count { instance =>
        instance.runSpecVersion < runSpec.version && considerTerminal(instance.state.condition) && instance.state.goal != Goal.Running
      }

      val oldInstances = instances.valuesIterator.count(_.runSpecVersion < runSpec.version) - oldTerminalInstances

      val newInstancesStarted = instances.valuesIterator.count { instance =>
        instance.runSpecVersion == runSpec.version && instance.state.goal == Goal.Running
      }
      launchInstances(oldInstances, newInstancesStarted).pipeTo(self)

    case Scheduled(scheduledInstances) =>
      logPrefixedInfo("launching")(s"Marking ${scheduledInstances.map(_.instanceId)} as scheduled.")
      // We take note of all scheduled instances before accepting new updates so that we do not overscale.
      scheduledInstances.foreach { instance =>
        // The launch queue actor does not change instances so we have to ensure that the goal is running.
        // These instance will be overridden by new updates but for now we just need to know that we scheduled them.
        val updatedState = instance.state.copy(goal = Goal.Running)
        instances += instance.instanceId -> instance.copy(state = updatedState, runSpec = runSpec)
      }
      context.become(updating)

      // We went through all phases so lets unleash all pending instance changed updates.
      unstashAll()

    // Stash all instance changed events
    case stashMe: AnyRef =>
      stash()
  }

  def launchInstances(oldInstances: Int, newInstancesStarted: Int): Future[Scheduled] = {
    val leftCapacity = math.max(0, ignitionStrategy.maxCapacity - oldInstances - newInstancesStarted)
    val instancesNotStartedYet = math.max(0, runSpec.instances - newInstancesStarted)
    val instancesToStartNow = math.min(instancesNotStartedYet, leftCapacity)

    if (instancesToStartNow > 0) {
      logPrefixedInfo("launching")(s"Queuing $instancesToStartNow new instances")
      launchQueue.addWithReply(runSpec, instancesToStartNow).map(instances => Scheduled(instances))
    } else {
      logPrefixedInfo("launching")("Not queuing new instances")
      Future.successful(Scheduled(Seq.empty))
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def killNextOldInstance(dequeued: Instance): Future[Done] = {
    async {
      await(instanceTracker.get(dequeued.instanceId)) match {
        case None =>
          logger.warn(s"Was about to kill instance ${dequeued} but it did not exist in the instance tracker anymore.")
          Done
        case Some(nextOldInstance) =>
          logPrefixedInfo("killing")(s"Killing old ${nextOldInstance.instanceId}")

          if (runSpec.isResident) {
            await(instanceTracker.setGoal(nextOldInstance.instanceId, Goal.Stopped))
          } else {
            await(instanceTracker.setGoal(nextOldInstance.instanceId, Goal.Decommissioned))
          }
          await(killService.killInstance(nextOldInstance, KillReason.Upgrading))
      }
    }
  }

  protected val hasHealthChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.healthChecks.nonEmpty
      case pod: PodDefinition => pod.containers.exists(_.healthCheck.isDefined)
    }
  }

  def logPrefixedInfo(phase: String)(msg: String): Unit = logger.info(s"Deployment ${status.plan.id} Phase $phase: $msg")

  // TODO(karsten): Remove ReadinesscheckBehaviour duplication.
  private[this] var subscriptions = Map.empty[ReadinessCheckSubscriptionKey, Cancellable]

  protected val hasReadinessChecks: Boolean = {
    runSpec match {
      case app: AppDefinition => app.readinessChecks.nonEmpty
      case pod: PodDefinition => false // TODO(PODS) support readiness post-MVP
    }
  }

  def unsubscripeReadinessCheck(result: ReadinessCheckResult): Unit = {
    val subscriptionName = ReadinessCheckSubscriptionKey(result.taskId, result.name)
    subscriptions.get(subscriptionName).foreach(_.cancel())
  }

  def initiateReadinessCheck(instance: Instance): Unit = {
    instance.tasksMap.foreach {
      case (_, task) => initiateReadinessCheckForTask(task)
    }
  }

  implicit private val materializer = ActorMaterializer()
  private def initiateReadinessCheckForTask(task: Task): Unit = {

    logger.info(s"Schedule readiness check for task: ${task.taskId}")
    ReadinessCheckExecutor.ReadinessCheckSpec.readinessCheckSpecsForTask(runSpec, task).foreach { spec =>
      val subscriptionName = ReadinessCheckSubscriptionKey(task.taskId, spec.checkName)
      val (subscription, streamDone) = readinessCheckExecutor.execute(spec)
        .toMat(Sink.foreach { result: ReadinessCheckResult => self ! result })(Keep.both)
        .run
      streamDone.onComplete { doneResult =>
        self ! ReadinessCheckStreamDone(subscriptionName, doneResult.failed.toOption)
      }(context.dispatcher)
      subscriptions = subscriptions + (subscriptionName -> subscription)
    }
  }
}

object TaskReplaceActor extends StrictLogging {

  object CheckFinished

  //scalastyle:off
  def props(
    deploymentManagerActor: ActorRef,
    status: DeploymentStatus,
    killService: KillService,
    launchQueue: LaunchQueue,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    app: RunSpec,
    promise: Promise[Unit]): Props = Props(
    new TaskReplaceActor(deploymentManagerActor, status, killService, launchQueue, instanceTracker, eventBus,
      readinessCheckExecutor, app, promise)
  )

  /** Encapsulates the logic how to get a Restart going */
  private[impl] case class RestartStrategy(nrToKillImmediately: Int, maxCapacity: Int)

  private[impl] def computeRestartStrategy(runSpec: RunSpec, runningInstancesCount: Int): RestartStrategy = {
    // in addition to a spec which passed validation, we require:
    require(runSpec.instances > 0, s"instances must be > 0 but is ${runSpec.instances}")
    require(runningInstancesCount >= 0, s"running instances count must be >=0 but is $runningInstancesCount")

    val minHealthy = (runSpec.instances * runSpec.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    var maxCapacity = (runSpec.instances * (1 + runSpec.upgradeStrategy.maximumOverCapacity)).toInt
    var nrToKillImmediately = math.max(0, runningInstancesCount - minHealthy)

    if (minHealthy == maxCapacity && maxCapacity <= runningInstancesCount) {
      if (runSpec.isResident) {
        // Kill enough instances so that we end up with one instance below minHealthy.
        // TODO: We need to do this also while restarting, since the kill could get lost.
        nrToKillImmediately = runningInstancesCount - minHealthy + 1
        logger.info(
          "maxCapacity == minHealthy for resident app: " +
            s"adjusting nrToKillImmediately to $nrToKillImmediately in order to prevent over-capacity for resident app"
        )
      } else {
        logger.info("maxCapacity == minHealthy: Allow temporary over-capacity of one instance to allow restarting")
        maxCapacity += 1
      }
    }

    logger.info(s"For minimumHealthCapacity ${runSpec.upgradeStrategy.minimumHealthCapacity} of ${runSpec.id.toString} leave " +
      s"$minHealthy instances running, maximum capacity $maxCapacity, killing $nrToKillImmediately of " +
      s"$runningInstancesCount running instances immediately. (RunSpec version ${runSpec.version})")

    assume(nrToKillImmediately >= 0, s"nrToKillImmediately must be >=0 but is $nrToKillImmediately")
    assume(maxCapacity > 0, s"maxCapacity must be >0 but is $maxCapacity")
    def canStartNewInstances: Boolean = minHealthy < maxCapacity || runningInstancesCount - nrToKillImmediately < maxCapacity
    assume(canStartNewInstances, "must be able to start new instances")

    RestartStrategy(nrToKillImmediately = nrToKillImmediately, maxCapacity = maxCapacity)
  }
}

