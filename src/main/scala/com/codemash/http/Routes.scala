package com.codemash.http

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.util.Timeout
import com.codemash.actors.Greeter
import com.codemash.actors.Greeter.Greeted
import com.codemash.actors.FlakyWorker
import com.codemash.actors.RetryingWorker
import com.codemash.actors.PersistRetryWorker
import com.codemash.sharding.CounterEntity
import com.codemash.sharding.CounterEntity.{Ack, Value}
import spray.json._
import scala.concurrent.duration._
import java.util.UUID

import scala.concurrent.Future

trait JsonSupport extends DefaultJsonProtocol {
  implicit val greetedFmt = jsonFormat1(Greeted)
  implicit val ackFmt = jsonFormat2(Ack)
  implicit val valueFmt = jsonFormat2(Value)
}

class Routes(
  greeter: ActorRef[Greeter.Command],
  worker: ActorRef[FlakyWorker.Command],
  sharding: ClusterSharding,
  retrying: ActorRef[RetryingWorker.Command],
  persisted: ActorRef[PersistRetryWorker.Command]
)(implicit timeout: Timeout, system: akka.actor.typed.ActorSystem[_])
  extends JsonSupport {
  import system.executionContext

  private def counterRef(id: String): EntityRef[CounterEntity.Command] =
    sharding.entityRefFor(CounterEntity.TypeKey, id)

  val routes: Route = pathPrefix("api") {
    concat(
      path("hello") {
        get {
          parameter("name".?) { nameOpt =>
            val name = nameOpt.getOrElse("World")
            val f: Future[Greeted] = greeter.ask(ref => Greeter.Greet(name, ref))
            complete(f.map(_.toJson))
          }
        }
      },
      pathPrefix("work") {
        concat(
          path("fail") {
            post {
              parameter("rate".as[Double].withDefault(0.5)) { rate =>
                val id = UUID.randomUUID().toString
                worker ! FlakyWorker.DoWork(id, rate)
                complete(StatusCodes.Accepted, s"Work $id triggered with failureRate=$rate (supervision only)")
              }
            }
          },
          path("retry") {
            post {
              parameters("rate".as[Double].withDefault(0.5), "attempts".as[Int].withDefault(3), "delayMs".as[Long].withDefault(200L)) { (rate, attempts, delayMs) =>
                import RetryingWorker._
                val id = UUID.randomUUID().toString
                val totalWaitMs = (0 until attempts).foldLeft(0L) { (acc, i) => acc + (delayMs * math.pow(2, i).toLong) }
                val longTimeout: Timeout = Timeout((totalWaitMs + 2000).millis) // base + buffer
                val f = retrying.ask[Result](ref => TryWork(id, rate, attempts, delayMs.millis, ref))(longTimeout, system.scheduler)
                complete(f.map {
                  case Done          => JsObject("id" -> JsString(id), "status" -> JsString("done"))
                  case Failed(reason) => JsObject("id" -> JsString(id), "status" -> JsString("failed"), "reason" -> JsString(reason))
                })
              }
            }
          },
          path("persisted") {
            post {
              parameter("rate".as[Double].withDefault(0.5)) { rate =>
                import PersistRetryWorker._
                val id = UUID.randomUUID().toString
                val f = persisted.ask[Result](ref => TryWork(id, rate, ref))
                complete(f.map {
                  case Done           => JsObject("id" -> JsString(id), "status" -> JsString("intent-persisted"))
                  case Failed(reason) => JsObject("id" -> JsString(id), "status" -> JsString("failed"), "reason" -> JsString(reason))
                })
              }
            }
          }
        )
      },
      pathPrefix("counter" / Segment) { id =>
        concat(
          pathEndOrSingleSlash {
            get {
              val f: Future[Value] = counterRef(id).ask(ref => CounterEntity.Get(ref))
              complete(f.map(_.toJson))
            }
          },
          path("inc") {
            post {
              parameter("n".as[Int].withDefault(1)) { n =>
                val f: Future[Ack] = counterRef(id).ask(ref => CounterEntity.Increment(n, ref))
                complete(f.map(_.toJson))
              }
            }
          }
        )
      }
    )
  }
}
