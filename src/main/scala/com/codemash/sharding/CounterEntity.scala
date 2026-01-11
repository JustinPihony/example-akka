package com.codemash.sharding

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object CounterEntity {
  // Protocol
  sealed trait Command extends CborSerializable
  final case class Increment(n: Int, replyTo: ActorRef[Ack]) extends Command
  final case class Get(replyTo: ActorRef[Value]) extends Command

  // Replies
  sealed trait Response extends CborSerializable
  final case class Ack(id: String, newValue: Int) extends Response
  final case class Value(id: String, value: Int) extends Response

  // Events
  sealed trait Event extends CborSerializable
  final case class Incremented(n: Int) extends Event

  // State
  final case class State(value: Int) extends CborSerializable

  // Marker trait for serialization
  trait CborSerializable

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("CounterEntity")

  def apply(id: String): Behavior[Command] = Behaviors.setup { ctx =>
    ctx.log.info("Starting CounterEntity {}", id)

    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = State(0),
      commandHandler = (state, cmd) => commandHandler(id, state, cmd, ctx),
      eventHandler = (state, evt) => eventHandler(state, evt, ctx)
    )
  }

  private def commandHandler(id: String, state: State, cmd: Command, ctx: ActorContext[Command]): Effect[Event, State] = {
    cmd match {
      case Increment(n, replyTo) =>
        Effect.persist(Incremented(n)).thenRun { (newState: State) =>
          ctx.log.info("CounterEntity {} incremented by {}. New value: {}", id, n, newState.value)
        }.thenReply(replyTo)(newState => Ack(id, newState.value))
      case Get(replyTo) =>
        replyTo ! Value(id, state.value)
        Effect.none
    }
  }

  private def eventHandler(state: State, evt: Event, ctx: ActorContext[Command]): State = {
    evt match {
      case Incremented(n) =>
        val newState = state.copy(value = state.value + n)
        ctx.log.info("CounterEntity recovered event Incremented({}). New value: {}", n, newState.value)
        newState
    }
  }
}
