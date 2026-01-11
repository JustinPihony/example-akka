package com.codemash

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.codemash.actors.{FlakyWorker, Greeter}
import com.codemash.actors.RetryingWorker
import com.codemash.actors.PersistRetryWorker
import com.codemash.http.Routes
import com.codemash.sharding.CounterEntity

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Main {
  def main(args: Array[String]): Unit = {
    val root = Behaviors.setup[Nothing] { ctx =>
      implicit val system = ctx.system
      implicit val ec = ctx.executionContext

      // Supervised worker to demonstrate fault tolerance
      val workerBehavior = Behaviors.supervise(FlakyWorker())
        .onFailure[RuntimeException](
          SupervisorStrategy
            .restartWithBackoff(minBackoff = 500.millis, maxBackoff = 5.seconds, randomFactor = 0.2)
            .withLoggingEnabled(true)
        )
      val worker = ctx.spawn(workerBehavior, name = "flaky-worker")

      // Greeter for ask pattern
      val greeter = ctx.spawn(Greeter(), "greeter")

      // Retrying (Option A) and Persisted (Option B) workers
      val retrying = ctx.spawn(RetryingWorker(), "retrying-worker")

      // Persisted worker needs supervision to restart on failure
      val persistedBehavior = Behaviors.supervise(PersistRetryWorker("persist-1"))
        .onFailure[RuntimeException](SupervisorStrategy.restart)

      val persisted = ctx.spawn(persistedBehavior, "persist-worker")

      // Cluster Sharding for counters
      val sharding = ClusterSharding(system)
      val typeKey: EntityTypeKey[CounterEntity.Command] = CounterEntity.TypeKey
      sharding.init(Entity(typeKey) { entityContext =>
        CounterEntity(entityContext.entityId)
      })

      // HTTP routes
      implicit val timeout: Timeout = 3.seconds
      val routes = new Routes(greeter, worker, sharding, retrying, persisted).routes

      val host = system.settings.config.getString("app.http.host")
      val port = system.settings.config.getInt("app.http.port")

      Http().newServerAt(host, port).bind(routes).onComplete {
        case Success(binding) =>
          val addr = binding.localAddress
          system.log.info("HTTP server online at http://{}:{}/", addr.getHostString, addr.getPort)
        case Failure(ex) =>
          system.log.error("Failed to bind HTTP endpoint", ex)
          system.terminate()
      }

      Behaviors.empty
    }

    ActorSystem[Nothing](root, "AkkaActorModelDemo")
  }
}
