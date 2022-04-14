package edomata.backend

import cats.data.NonEmptyChain
import cats.effect.Concurrent
import cats.implicits.*
import edomata.core.*
import fs2.Pipe
import fs2.Stream

import java.time.OffsetDateTime
import java.util.UUID

enum AggregateState[S, E, R](val isValid: Boolean) {
  case Valid(state: Model.Of[S, E, R], version: SeqNr)
      extends AggregateState[S, E, R](true)
  case Conflicted(
      last: Model.Of[S, E, R],
      onEvent: EventMessage[E],
      errors: NonEmptyChain[R]
  ) extends AggregateState[S, E, R](false)
}

trait Repository[F[_], S, E, R] {
  def get(streamId: StreamId): F[AggregateState[S, E, R]]
  def history(streamId: StreamId): Stream[F, AggregateState[S, E, R]]
}

object Repository {
  def apply[F[_]: Concurrent, S, E, R](
      initial: Model.Of[S, E, R],
      journal: JournalReader[F, E],
      snapshot: SnapshotStore[F, S, E, R]
  ): Repository[F, S, E, R] = new Repository {
    private val F = Concurrent[F]

    def get(streamId: StreamId): F[AggregateState[S, E, R]] =
      snapshot
        .get(streamId)
        .flatMap {
          case Some(last) =>
            journal
              .readStreamAfter(streamId, last.version)
              .through(read(last))
              .compile
              .lastOrError
          case None => history(streamId).compile.lastOrError
        }
        .flatTap {
          case s @ AggregateState.Valid(_, _) => snapshot.put(streamId, s)
          case _                              => F.unit
        }

    def read(
        last: AggregateState[S, E, R]
    ): Pipe[F, EventMessage[E], AggregateState[S, E, R]] =
      _.scan(last) {
        case (AggregateState.Valid(s, version), ev) =>
          s.transition(ev.payload)
            .fold(
              AggregateState.Conflicted(s, ev, _),
              AggregateState.Valid(_, version + 1)
            )
        case (other, ev) => other
      }

    def history(streamId: StreamId): Stream[F, AggregateState[S, E, R]] =
      journal
        .readStream(streamId)
        .through(read(AggregateState.Valid(initial, 0L)))
        .takeWhile(_.isValid, true)
  }
}