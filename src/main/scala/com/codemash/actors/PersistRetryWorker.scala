package com.codemash.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}

import scala.util.Random
import scala.concurrent.duration._

object PersistRetryWorker {
  // Commands
  sealed trait Command
  final case class TryWork(id: String, rate: Double, replyTo: ActorRef[Result]) extends Command
  private final case object Execute extends Command

  // Replies
  sealed trait Result
  case object Done extends Result
  final case class Failed(reason: String) extends Result

  // Events
  sealed trait Event
  final case class WorkRequested(id: String, rate: Double) extends Event
  final case object WorkSucceeded extends Event
  final case class WorkFailed(reason: String) extends Event

  // State
  final case class State(pending: Option[(String, Double)])

  def apply(id: String): Behavior[Command] = Behaviors.setup { ctx =>
    ctx.log.info("PersistRetryWorker {} started", id)

    EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = State(None),
      commandHandler = (state, cmd) => onCommand(state, cmd, ctx),
      eventHandler = onEvent
    ).receiveSignal {
      case (state, RecoveryCompleted) =>
        if (state.pending.isDefined) {
          val (workId, _) = state.pending.get
          ctx.log.info("Recovery completed with pending work {}, retrying execution", workId)
          ctx.self ! Execute
        }
    }
  }

  private def onCommand(state: State, cmd: Command, ctx: akka.actor.typed.scaladsl.ActorContext[Command]): ReplyEffect[Event, State] = {
    cmd match {
      case TryWork(id, rate, replyTo) =>
        Effect.persist(WorkRequested(id, rate)).thenRun { _: State =>
          ctx.self ! Execute
        }.thenReply(replyTo)(_ => Done) // immediate ack of intent (at-least-once intent persisted)
      case Execute =>
        state.pending match {
          case Some((id, rate)) =>
            val r = Random.nextDouble()
            if (r < rate) {
              ctx.log.warn("PersistRetryWorker execution failed (id={}, r={}, rate={}) — will retry after restart", id, r: java.lang.Double, rate: java.lang.Double)
              // simulate crash -> restart, which will replay the event and re-execute
              throw new RuntimeException(s"simulated failure for $id; restart will retry")
            } else {
              Effect.persist(WorkSucceeded)
                .thenRun { _: State =>
                  ctx.log.info("PersistRetryWorker execution succeeded (id={}, r={}, rate={})", id, r: java.lang.Double, rate: java.lang.Double)
                }
                .thenNoReply()
            }
          case None =>
            Effect.unhandled.thenNoReply()
        }
    }
  }

  private def onEvent(state: State, evt: Event): State = evt match {
    case WorkRequested(id, rate) => state.copy(pending = Some((id, rate)))
    case WorkSucceeded           => state.copy(pending = None)
    case WorkFailed(_)           => state // not used in this simplified demo
  }
}
