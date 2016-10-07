package com.vivint.ceph

import akka.actor.{ ActorRef, ActorSystem, PoisonPill, Props }
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Keep, Sink }
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory, ConfigRenderOptions }
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64.getDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import mesosphere.mesos.protos.Resource.{CPUS, MEM, PORTS, DISK}
import org.apache.commons.io.FileUtils
import org.apache.mesos.Protos
import org.scalatest.Inside
import org.scalatest.exceptions.TestFailedException
import org.scalatest.{ BeforeAndAfterAll, FunSpecLike, Matchers }
import com.vivint.ceph.kvstore.KVStore
import com.vivint.ceph.lib.TgzHelper
import com.vivint.ceph.model.{ PersistentState, TaskRole, Task, RunState, ServiceLocation }
import com.vivint.ceph.views.ConfigTemplates
import scala.annotation.tailrec
import scala.collection.breakOut
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Awaitable, ExecutionContext }
import scala.reflect.ClassTag
import scaldi.Injectable._
import scaldi.{ Injector, Module }

class IntegrationTest extends TestKit(ActorSystem("integrationTest"))
    with FunSpecLike with Matchers with BeforeAndAfterAll with Inside {

  val idx = new AtomicInteger()
  val fileStorePath = new File("tmp/test-store")
  import ProtoHelpers._

  def await[T](f: Awaitable[T], duration: FiniteDuration = 5.seconds) = {
    Await.result(f, duration)
  }

  def renderConfig(cfg: Config) = {
    cfg.root().render(ConfigRenderOptions.defaults.setOriginComments(false))
  }

  override def beforeAll(): Unit = {
    FileUtils.deleteDirectory(fileStorePath)
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  trait TestBindings extends Module {
    val id = idx.incrementAndGet()
    bind [TestProbe] to { TestProbe() } destroyWith {
      _.ref ! PoisonPill
    }

    bind [ActorRef] identifiedBy(classOf[FrameworkActor]) to { inject[TestProbe].ref }
    bind [ActorRef] identifiedBy(classOf[TaskActor]) to {
      system.actorOf(Props(new TaskActor), s"task-actor-${id}")
    } destroyWith { _ ! PoisonPill }

    // bind [KVStore] to { new kvstore.FileStore(new File(fileStorePath, idx.incrementAndGet.toString)) }
    bind [KVStore] to { new kvstore.MemStore }
    bind [OfferOperations] to new OfferOperations
    bind [FrameworkIdStore] to new FrameworkIdStore
    bind [ConfigTemplates] to new ConfigTemplates
    bind [ActorSystem] to system
    bind [AppConfiguration] to {
      AppConfiguration(master = "hai", name = "ceph-test", principal = "ceph", secret = None, role = "ceph",
        zookeeper = "zk://test", offerTimeout = 5.seconds, publicNetwork = "10.11.12.0/24",
        clusterNetwork =  "10.11.12.0/24", storageBackend = "memory")
    }
    bind [String => String] identifiedBy 'ipResolver to { _: String =>
      "10.11.12.1"
    }
  }

  def cephConfUpdates(implicit inj: Injector) = {
    inject[KVStore].watch("ceph.conf").
      map { _.map { bytes => ConfigFactory.parseString(new String(bytes)) } }.
      collect { case Some(cfg) => cfg }
  }

  def storeConfig(implicit inj: Injector) =
    Flow[Config].
      mapAsync(1) { cfg =>
        inject[KVStore].set("ceph.conf", renderConfig(cfg).getBytes)
      }.toMat(Sink.ignore)(Keep.right)

  def updateConfig(newCfg: String)(implicit inj: Injector) = {
    implicit val materializer = ActorMaterializer()
    await {
      cephConfUpdates.
        take(1).
        map { cfg =>
          ConfigFactory.parseString(newCfg).withFallback(cfg)
        }.
        runWith(storeConfig)
    }
  }

  @tailrec final def receiveIgnoring(probe: TestProbe, d: FiniteDuration, ignore: PartialFunction[Any, Boolean]): Any = {
    val received = probe.receiveOne(d)
      if (ignore.isDefinedAt(received) && ignore(received))
        receiveIgnoring(probe, d, ignore)
      else
        received
  }

  @tailrec final def gatherResponses(testProbe: TestProbe, offers: List[Protos.Offer],
    results: Map[Protos.Offer, FrameworkActor.OfferResponseCommand] = Map.empty,
    ignore: PartialFunction[Any, Boolean] = { case _ => false }):
      Map[Protos.Offer, FrameworkActor.OfferResponseCommand] = {
    if (offers.isEmpty)
      results
    else {
      val received = receiveIgnoring(testProbe, 5.seconds, ignore)
      val (newResults, newOffers) = inside(received) {
        case msg: FrameworkActor.OfferResponseCommand =>
          val offer = offers.find(_.getId == msg.offerId).get
          ( results.updated(offer, msg),
            offers.filterNot(_ == offer))
      }
      gatherResponses(testProbe, newOffers, newResults, ignore)
    }
  }

  def gatherResponse(testProbe: TestProbe, offer: Protos.Offer, ignore: PartialFunction[Any, Boolean] = { case _ => false }):
      FrameworkActor.OfferResponseCommand = {
    gatherResponses(testProbe, List(offer), ignore = ignore)(offer)
  }

  def handleGetCreateReserve: PartialFunction[Any, (Protos.Offer.Operation.Reserve, Protos.Offer.Operation.Create)] = {
    case offerResponse: FrameworkActor.AcceptOffer =>
      val List(reserve, create) = offerResponse.operations
      reserve.hasReserve shouldBe (true)
      create.hasCreate shouldBe (true)
      (reserve.getReserve, create.getCreate)
  }

  def handleReservationResponse(offer: Protos.Offer): PartialFunction[Any, Protos.Offer] = handleGetCreateReserve.andThen {
    case (reserve, create) =>
      MesosTestHelper.mergeReservation(offer, reserve, create)
  }

  val ignoreRevive: PartialFunction[Any,Boolean] = { case FrameworkActor.ReviveOffers => true }

  def readConfig(configMD5: String): Map[String, String] =
    TgzHelper.readTgz(getDecoder.decode(configMD5)).map {
      case (k, v) => (k, new String(v, UTF_8))
    }.toMap


  it("should launch a monitors on unique hosts") {
    implicit val ec: ExecutionContext = SameThreadExecutionContext
    val module = new TestBindings {}
    import module.injector

    val taskActor = inject[ActorRef](classOf[TaskActor])
    val probe = inject[TestProbe]
    implicit val sender = probe.ref

    inject[FrameworkIdStore].set(MesosTestHelper.frameworkID)

    val kvStore = inject[KVStore]
    val configStore = new ConfigStore(inject[KVStore])
    implicit val materializer = ActorMaterializer()

    // Wait for configuration update
    val config = await(cephConfUpdates.runWith(Sink.head))

    updateConfig("deployment.mon.count = 2")

    probe.receiveOne(5.seconds) shouldBe FrameworkActor.ReviveOffers

    val offer = MesosTestHelper.makeBasicOffer(slaveId = 0).build
    val sameOffer = MesosTestHelper.makeBasicOffer(slaveId = 0).build

    // Send an offer!
    taskActor ! FrameworkActor.ResourceOffers(List(offer, sameOffer))

    val responses = gatherResponses(probe, List(offer, sameOffer))

    val reservedOffer = inside(responses(offer))(handleReservationResponse(offer))

    inside(responses(sameOffer)) {
      case offerResponse: FrameworkActor.DeclineOffer =>
        offerResponse.offerId shouldBe sameOffer.getId
    }

    // Send the reservation
    taskActor ! FrameworkActor.ResourceOffers(List(sameOffer))
    probe.receiveOne(5.seconds) shouldBe a[FrameworkActor.DeclineOffer]

    taskActor ! FrameworkActor.ResourceOffers(List(reservedOffer))

    val taskId = inside(probe.receiveOne(5.seconds)) {
      case offerResponse: FrameworkActor.AcceptOffer =>
        offerResponse.offerId shouldBe reservedOffer.getId
        offerResponse.operations(0).getType shouldBe Protos.Offer.Operation.Type.LAUNCH
        val List(task) = offerResponse.operations(0).getLaunch.tasks
        val shCommand = task.getCommand.getValue
        shCommand.contains("entrypoint.sh") shouldBe true
        shCommand.contains("ceph mon getmap") shouldBe false
        task.getTaskId
    }

    // And, again, let's make sure it rejects it
    taskActor ! FrameworkActor.ResourceOffers(List(sameOffer))
    probe.receiveOne(5.seconds) shouldBe a[FrameworkActor.DeclineOffer]

    // Now let's set the offer to running
    taskActor ! FrameworkActor.StatusUpdate(newTaskStatus(
      taskId = taskId.getValue,
      slaveId = reservedOffer.getSlaveId.getValue,
      state = Protos.TaskState.TASK_RUNNING))


    // Now, let's make an offer for the second one!
    val altSlaveOffer = MesosTestHelper.makeBasicOffer(slaveId = 1).build
    taskActor ! FrameworkActor.ResourceOffers(List(altSlaveOffer))

    val altReservedOffer = inside(probe.receiveOne(5.seconds))(handleReservationResponse(altSlaveOffer))

    taskActor ! FrameworkActor.ResourceOffers(List(altReservedOffer))

    inside(gatherResponse(probe, altReservedOffer, ignoreRevive)) {
      case offerResponse: FrameworkActor.AcceptOffer =>
        offerResponse.operations(0).getType shouldBe Protos.Offer.Operation.Type.LAUNCH
        val List(task) = offerResponse.operations(0).getLaunch.tasks
        val shCommand = task.getCommand.getValue
        shCommand.contains("entrypoint.sh") shouldBe true
        shCommand.contains("ceph mon getmap") shouldBe true
    }

    module.destroy(_ => true)
  }

  trait OneMonitorRunning {
    implicit val ec: ExecutionContext = SameThreadExecutionContext
    val monLocation = ServiceLocation(hostname = "slave-12", ip = "10.11.12.12", port = 30125)
    val monitorTask = PersistentState(
      id = UUID.randomUUID(),
      cluster = "ceph",
      role = TaskRole.Monitor,
      lastLaunched = Some(RunState.Running),
      goal = Some(RunState.Running),
      reservationConfirmed = true,
      slaveId = Some("slave-12"),
      location = Some(monLocation))
    val monitorTaskTaskId = model.Task.makeTaskId(monitorTask.role, monitorTask.cluster, monitorTask.id)

    val module = new TestBindings {
      bind [KVStore] to {
        val store = new kvstore.MemStore
        val taskStore = new TaskStore(store)
        taskStore.save(monitorTask)
        store
      }
    }

    import module.injector

    val taskActor = inject[ActorRef](classOf[TaskActor])
    val probe = inject[TestProbe]
    implicit val sender = probe.ref

    inject[FrameworkIdStore].set(MesosTestHelper.frameworkID)

    val kvStore = inject[KVStore]
    val configStore = new ConfigStore(inject[KVStore])
    implicit val materializer = ActorMaterializer()

    // Wait for configuration update
    val config = await(cephConfUpdates.runWith(Sink.head))

    inside(probe.receiveOne(5.seconds)) {
      case r: FrameworkActor.Reconcile =>
        r.tasks.length shouldBe 1
        val taskStatus = r.tasks.head
        taskActor ! FrameworkActor.StatusUpdate(taskStatus.toBuilder.setState(Protos.TaskState.TASK_RUNNING).build)
    }
  }

  it("should launch OSDs") {
    new OneMonitorRunning {
      import module.injector
      updateConfig("deployment.osd.count = 1")
      probe.receiveOne(5.seconds) shouldBe FrameworkActor.ReviveOffers

      val offer = MesosTestHelper.makeBasicOffer(slaveId = 0).
        addResources(newScalarResource(
          DISK, 1024000.0, disk = Some(MesosTestHelper.mountDisk("/mnt/ssd-1")))).build
      taskActor ! FrameworkActor.ResourceOffers(List(offer))

      val reservedOffer = inside(gatherResponse(probe, offer, ignoreRevive))(handleReservationResponse(offer))

      reservedOffer.resources.filter(_.getName == DISK).head.getScalar.getValue shouldBe 1024000.0

      taskActor ! FrameworkActor.ResourceOffers(List(reservedOffer))

      inside(gatherResponse(probe, reservedOffer, ignoreRevive)) {
        case offerResponse: FrameworkActor.AcceptOffer =>
          offerResponse.operations(0).getType shouldBe Protos.Offer.Operation.Type.LAUNCH
          val List(task) = offerResponse.operations(0).getLaunch.tasks
          val shCommand = task.getCommand.getValue
          shCommand.contains("entrypoint.sh osd_directory") shouldBe true
          val config = readConfig(task.getCommand.getEnvironment.get("CEPH_CONFIG_TGZ").get)
          val cephConfig = config("etc/ceph/ceph.conf")
          cephConfig.lines.filter(_.contains("ms_bind_port")).toList shouldBe List(
            "ms_bind_port_min = 31000",
            "ms_bind_port_max = 31004")
          cephConfig.lines.filter(_.startsWith("mon")).take(3).toList shouldBe List(
            s"mon initial members = ${monLocation.hostname}",
            s"mon host = ${monLocation.hostname}:${monLocation.port}",
            s"mon addr = ${monLocation.ip}:${monLocation.port}")
      }
    }
  }


  it("should kill a task in response to a goal change") {
    new OneMonitorRunning {
      import module.injector

      val taskKilledStatusUpdate = newTaskStatus(monitorTaskTaskId, monitorTask.slaveId.get,
        state = Protos.TaskState.TASK_KILLED)
      val taskRunningStatusUpdate = newTaskStatus(monitorTaskTaskId, monitorTask.slaveId.get,
        state = Protos.TaskState.TASK_RUNNING)

      taskActor ! TaskActor.UpdateGoal(monitorTaskTaskId, model.RunState.Paused)

      inside(receiveIgnoring(probe, 5.seconds, ignoreRevive)) {
        case FrameworkActor.KillTask(taskId) =>
          taskId.getValue shouldBe monitorTaskTaskId
      }

      taskActor ! FrameworkActor.StatusUpdate(taskKilledStatusUpdate)

      val offerOperations = inject[OfferOperations]
      val reservedOffer = MesosTestHelper.makeBasicOffer(
        slaveId = 12,
        role = "ceph",
        reservationLabels = Some(newLabels(
          Constants.FrameworkIdLabel -> MesosTestHelper.frameworkID.getValue,
          Constants.TaskIdLabel -> monitorTaskTaskId))).
        build

      taskActor ! FrameworkActor.ResourceOffers(List(reservedOffer))

      inside(gatherResponse(probe, reservedOffer, ignoreRevive)) {
        case offerResponse: FrameworkActor.AcceptOffer =>
          offerResponse.operations(0).getType shouldBe Protos.Offer.Operation.Type.LAUNCH
          val List(task) = offerResponse.operations(0).getLaunch.tasks
          val shCommand = task.getCommand.getValue
          shCommand.contains("sleep ") shouldBe true
      }

      taskActor ! TaskActor.UpdateGoal(monitorTaskTaskId, model.RunState.Running)

      taskActor ! FrameworkActor.StatusUpdate(taskRunningStatusUpdate)

      inside(receiveIgnoring(probe, 5.seconds, ignoreRevive)) {
        case FrameworkActor.KillTask(taskId) =>
          taskId.getValue shouldBe monitorTaskTaskId
      }

      taskActor ! FrameworkActor.StatusUpdate(taskKilledStatusUpdate)

      taskActor ! FrameworkActor.ResourceOffers(List(reservedOffer))

      inside(gatherResponse(probe, reservedOffer, ignoreRevive)) {
        case offerResponse: FrameworkActor.AcceptOffer =>
          offerResponse.operations(0).getType shouldBe Protos.Offer.Operation.Type.LAUNCH
          val List(task) = offerResponse.operations(0).getLaunch.tasks
          val shCommand = task.getCommand.getValue
          shCommand.contains("entrypoint.sh mon") shouldBe true
      }
    }
  }

  it("should require that the first monitor is running before launching another") {
    implicit val ec: ExecutionContext = SameThreadExecutionContext
    val module = new TestBindings {}
    import module.injector

    val taskActor = inject[ActorRef](classOf[TaskActor])
    val probe = inject[TestProbe]
    implicit val sender = probe.ref

    inject[FrameworkIdStore].set(MesosTestHelper.frameworkID)

    val kvStore = inject[KVStore]
    val configStore = new ConfigStore(inject[KVStore])
    implicit val materializer = ActorMaterializer()

    // Wait for configuration update
    val config = await(cephConfUpdates.runWith(Sink.head))

    updateConfig("deployment.mon.count = 2")

    probe.receiveOne(5.seconds) shouldBe FrameworkActor.ReviveOffers

    val offers = List(
      MesosTestHelper.makeBasicOffer(slaveId = 0).build,
      MesosTestHelper.makeBasicOffer(slaveId = 1).build)

    // Send an offer!
    taskActor ! FrameworkActor.ResourceOffers(offers)

    val responses = gatherResponses(probe, offers, ignore = ignoreRevive)

    val List(reservationOffer, reservationOffer2) = responses.map { case (offer, response) =>
      inside(response)(handleReservationResponse(offer))
    }.toList

    taskActor ! FrameworkActor.ResourceOffers(List(reservationOffer, reservationOffer2))


    val launchedTaskId = inside(gatherResponse(probe, reservationOffer, ignoreRevive)) {
      case offerResponse: FrameworkActor.AcceptOffer =>
        offerResponse.operations(0).getType shouldBe Protos.Offer.Operation.Type.LAUNCH
        val List(task) = offerResponse.operations(0).getLaunch.tasks
        val shCommand = task.getCommand.getValue
        shCommand.contains("entrypoint.sh mon") shouldBe true
        shCommand.contains("ceph mon getmap") shouldBe false
        task.getTaskId.getValue
    }

    implicit val timeout = Timeout(3.seconds)

    val (Seq(launchedTask), Seq(unlaunchedTask)) = await((taskActor ? TaskActor.GetTasks).mapTo[Map[String, Task]]).
      values.
      partition(_.taskId == launchedTaskId)

    unlaunchedTask.behavior.name shouldBe ("Sleep")

    taskActor ! TaskActor.TaskTimer(unlaunchedTask.taskId, "wakeup")

    val snapshotAfterWake = await((taskActor ? TaskActor.GetTasks).mapTo[Map[String, Task]])(unlaunchedTask.taskId)

    unlaunchedTask.behavior.name shouldBe ("Sleep")
  }
}
