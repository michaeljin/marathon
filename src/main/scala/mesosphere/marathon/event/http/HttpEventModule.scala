package mesosphere.marathon.event.http

import scala.language.postfixOps
import com.google.inject.{Scopes, Singleton, Provides, AbstractModule}
import akka.actor.{Props, ActorRef, ActorSystem}
import akka.pattern.ask
import com.google.inject.name.Named
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import org.rogach.scallop.ScallopConf
import java.util.logging.Logger
import scala.concurrent.duration._
import akka.util.Timeout
import org.apache.mesos.state.State
import mesosphere.marathon.state.MarathonStore
import mesosphere.marathon.Main
import mesosphere.marathon.event.{MarathonSubscriptionEvent, Subscribe}

trait HttpEventConfiguration extends ScallopConf {

  lazy val httpEventEndpoints = opt[List[String]]("http_endpoints",
    descr = "The URLs of the event endpoints master",
    required = false,
    noshort = true)
}

class HttpEventModule extends AbstractModule {

  val log = Logger.getLogger(getClass.getName)

  def configure() {
    bind(classOf[HttpCallbackEventSubscriber]).asEagerSingleton()
    bind(classOf[HttpCallbackSubscriptionService]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  def provideActorSystem(): ActorSystem = {
    ActorSystem("MarathonEvents")
  }

  @Provides
  @Named(HttpEventModule.StatusUpdateActor)
  def provideStatusUpdateActor(system: ActorSystem,
                               @Named(HttpEventModule.SubscribersKeeperActor)
                               subscribersKeeper: ActorRef): ActorRef = {
    system.actorOf(Props(new HttpEventActor(subscribersKeeper)))
  }

  @Provides
  @Named(HttpEventModule.SubscribersKeeperActor)
  def provideSubscribersKeeperActor(system: ActorSystem,
                                    store: MarathonStore[EventSubscribers]): ActorRef = {
    implicit val timeout = HttpEventModule.timeout
    implicit val ec = HttpEventModule.executionContext
    val local_ip = java.net.InetAddress.getLocalHost().getHostAddress()

    val actor = system.actorOf(Props(new SubscribersKeeperActor(store)))
    Main.conf.httpEventEndpoints.get map {
      urls =>
        log.info(s"http_endpoints(${urls}) are specified at startup. Those will be added to subscribers list.")
        urls.foreach{ url =>
          val f = (actor ? Subscribe(local_ip, url)).mapTo[MarathonSubscriptionEvent]
          f.onFailure {
            case th: Throwable =>
              log.warning(s"Failed to add ${url} to event subscribers. exception message => ${th.getMessage}")
          }
        }
    }

    actor
  }

  @Provides
  @Named(HttpEventModule.HealthCheckKeeperActor)
  def provideHealthCheckKeeperActor(system: ActorSystem,
    // this needs to be MarathonStore[HealthCheckSubscriber]
                                    store: MarathonStore[EventSubscribers]): ActorRef = {
    implicit val timeout = HttpEventModule.timeout
    implicit val ec = HttpEventModule.executionContext
    val local_ip = java.net.InetAddress.getLocalHost().getHostAddress()

    val actor = system.actorOf(Props(new HealthCheckKeeperActor(store)))
    Main.conf.httpEventEndpoints.get map {
      urls =>
        log.info(s"http_endpoints(${urls}) are specified at startup. Those will be added to subscribers list.")
        urls.foreach{ url =>
          val f = (actor ? Subscribe(local_ip, url)).mapTo[MarathonSubscriptionEvent]
          f.onFailure {
            case th: Throwable =>
              log.warning(s"Failed to add ${url} to event subscribers. exception message => ${th.getMessage}")
          }
        }
    }

    actor
  }

  @Provides
  @Singleton
  def provideCallbackUrlsStore(state: State): MarathonStore[EventSubscribers] = {
    new MarathonStore[EventSubscribers](state, () => new EventSubscribers(Set.empty[String]), "events:")
  }

//  @Provides
//  @Singleton
//  def provideCallbackSubscriber(@Named(EventModule.busName) bus: Option[EventBus],
//    @Named(HttpEventModule.StatusUpdateActor) actor : ActorRef): HttpCallbackEventSubscriber = {
//    val callback = new HttpCallbackEventSubscriber(actor)
//    if (bus.nonEmpty) {
//      bus.get.register(callback)
//      log.warning("Registered HttpCallbackEventSubscriber with Bus." )
//    }
//    callback
//  }
}

object HttpEventModule {
  final val StatusUpdateActor = "EventsActor"
  final val SubscribersKeeperActor = "SubscriberKeeperActor"
  final val HealthCheckKeeperActor = "HealthCheckKeeperActor"

  val executorService = Executors.newCachedThreadPool()
  val executionContext = ExecutionContext.fromExecutorService(executorService)

  //TODO(everpeace) this should be configurable option?
  val timeout = Timeout(10 seconds)
}

