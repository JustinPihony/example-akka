package com.codemash.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

object Greeter {
  sealed trait Command
  final case class Greet(name: String, replyTo: ActorRef[Greeted]) extends Command
  final case class Greeted(message: String)

  def apply(): Behavior[Command] = Behaviors.receiveMessage {
    case Greet(name, replyTo) =>
      replyTo ! Greeted(s"Hello, $name! Welcome to the Actor Model.")
      Behaviors.same
  }
}
