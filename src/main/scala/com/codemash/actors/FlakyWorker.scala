package com.codemash.actors

import akka.actor.typed.{Behavior}
import akka.actor.typed.scaladsl.{Behaviors}
import scala.util.Random

object FlakyWorker {
  sealed trait Command
  final case class DoWork(id: String, failureRate: Double) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    ctx.log.info("FlakyWorker started")

    Behaviors.receiveMessage[Command] {
      case DoWork(id, rate) =>
        val r = Random.nextDouble()
        if (r < rate) {
          ctx.log.warn("FlakyWorker failing intentionally (id = {}, r = {}, rate = {})", id, r: java.lang.Double, rate: java.lang.Double)
          throw new RuntimeException(s"Intentional failure to demo supervision for work $id")
        } else {
          ctx.log.info("FlakyWorker completed work successfully (id = {}, r = {}, rate = {})", id, r: java.lang.Double, rate: java.lang.Double)
          Behaviors.same
        }
    }.receiveSignal {
      case (ctx, akka.actor.typed.PreRestart) =>
        ctx.log.info("FlakyWorker will restart")
        Behaviors.same
      case (ctx, akka.actor.typed.PostStop) =>
        ctx.log.info("FlakyWorker stopped")
        Behaviors.same
    }
  }
}
