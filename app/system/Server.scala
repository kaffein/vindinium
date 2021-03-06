package org.vindinium.server
package system

import akka.actor._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Try, Success, Failure }
import user.User

final class Server extends Actor with CustomLogging {

  import Server._

  val rounds = scala.collection.mutable.Map[GameId, ActorRef]()

  var nextArenaRoundId: Option[GameId] = None
  var nextArenaRoundClients: Int = 0

  def receive = akka.event.LoggingReceive {

    case RequestToPlayAlone(user, config) => {
      val replyTo = sender
      addRound(config) match {
        case Failure(e) => replyTo ! Status.Failure(e)
        case Success((_, round)) => {
          round ! Round.Join(user, inputPromise(replyTo))
          (1 to 3) foreach { _ =>
            round ! Round.JoinBot("random", Driver.Random)
          }
        }
      }
    }

    case RequestToPlayArena(user) => {
      val replyTo = sender
      (nextArenaRoundId.flatMap(rounds.get) match {
        case Some(round) if nextArenaRoundClients < 4 => {
          nextArenaRoundClients = nextArenaRoundClients + 1
          Success(round)
        }
        case _ => addRound(Config.arena) map {
          case (id, round) => {
            nextArenaRoundId = Some(id)
            nextArenaRoundClients = 1
            round
          }
        }
      }) match {
        case Failure(e)     => replyTo ! Status.Failure(e)
        case Success(round) => round ! Round.Join(user, inputPromise(sender))
      }
    }

    case Play(Pov(gameId, token), dir) => rounds get gameId match {
      case None        => sender ! notFound(s"Unknown game $gameId")
      case Some(round) => round.tell(Round.Play(token, dir), sender)
    }

    case Round.Inactive(id) => if (nextArenaRoundId != Some(id)) sender ! PoisonPill

    case Terminated(round) ⇒ {
      context unwatch round
      rounds filter (_._2 == round) foreach { case (id, _) ⇒ rounds -= id }
    }
  }

  def addRound(config: Config): Try[(GameId, ActorRef)] = config.make map { game =>
    val round = context.actorOf(Props(new Round(game)), name = game.id)
    rounds += (game.id -> round)
    context watch round
    game.id -> round
  }
}

object Server {

  case class RequestToPlayAlone(user: User, config: Config)
  case class RequestToPlayArena(user: User)

  case class Play(pov: Pov, dir: String)

  import play.api.libs.concurrent.Akka
  import play.api.Play.current
  val actor = Akka.system.actorOf(Props[Server], name = "server")
}
