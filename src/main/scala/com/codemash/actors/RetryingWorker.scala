package com.codemash.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}

import scala.concurrent.duration._
import scala.util.Random

object RetryingWorker {
  // Protocol
  sealed trait Command
  final case class TryWork(id: String, rate: Double, attempts: Int, initialDelay: FiniteDuration, replyTo: ActorRef[Result]) extends Command
  private final case class Retry(id: String, rate: Double, attemptsLeft: Int, delay: FiniteDuration, replyTo: ActorRef[Result]) extends Command

  // Replies
  sealed trait Result
  case object Done extends Result
  final case class Failed(reason: String) extends Result

  def apply(): Behavior[Command] = Behaviors.withTimers { timers =>
    Behaviors.setup { ctx =>
      idle(timers, ctx)
    }
  }

  private def idle(timers: TimerScheduler[Command], ctx: akka.actor.typed.scaladsl.ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case TryWork(id, rate, attempts, initialDelay, replyTo) =>
        ctx.log.info("RetryingWorker TryWork(id={}, rate={}, attempts={}, delay={})", id, rate: java.lang.Double, attempts: java.lang.Integer, initialDelay: Any)
        runAttempt(id, rate, attempts, initialDelay, replyTo, timers, ctx)
      case _ => Behaviors.same
    }

  private def busy(timers: TimerScheduler[Command], ctx: akka.actor.typed.scaladsl.ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case TryWork(id, _, _, _, replyTo) =>
        replyTo ! Failed(s"busy, cannot process $id")
        Behaviors.same
      case Retry(id, rate, left, delay, replyTo) =>
        continueAttempt(id, rate, left, delay, replyTo, timers, ctx)
    }

  private def runAttempt(id: String, rate: Double, attemptsLeft: Int, delay: FiniteDuration, replyTo: ActorRef[Result], timers: TimerScheduler[Command], ctx: akka.actor.typed.scaladsl.ActorContext[Command]): Behavior[Command] = {
    if (attemptsLeft <= 0) {
      replyTo ! Failed("exhausted")
      idle(timers, ctx)
    } else {
      val r = Random.nextDouble()
      if (r < rate) {
        // failure -> schedule retry after delay, double delay for next
        val nextDelay = (delay * 2).min(5.seconds)
        ctx.log.warn("RetryingWorker failure (id={}, r={}, rate={}). Retrying in {} ({} left)", id, r: java.lang.Double, rate: java.lang.Double, delay: Any, (attemptsLeft - 1): Any)
        timers.startSingleTimer(Retry(id, rate, attemptsLeft - 1, nextDelay, replyTo), delay)
        busy(timers, ctx)
      } else {
        ctx.log.info("RetryingWorker success (id={}, r={}, rate={})", id, r: java.lang.Double, rate: java.lang.Double)
        replyTo ! Done
        idle(timers, ctx)
      }
    }
  }

  private def continueAttempt(id: String, rate: Double, attemptsLeft: Int, delay: FiniteDuration, replyTo: ActorRef[Result], timers: TimerScheduler[Command], ctx: akka.actor.typed.scaladsl.ActorContext[Command]): Behavior[Command] = {
    runAttempt(id, rate, attemptsLeft, delay, replyTo, timers, ctx)
  }
}
