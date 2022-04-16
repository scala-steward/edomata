package edomata.backend

import cats.data.NonEmptyChain
import edomata.core.*
import fs2.Stream

import java.time.OffsetDateTime
import java.util.UUID

trait Journal[F[_], E] extends JournalReader[F, E], JournalWriter[F, E]

trait JournalWriter[F[_], E] {
  def append(
      streamId: StreamId,
      time: OffsetDateTime,
      version: SeqNr,
      events: NonEmptyChain[E]
  ): F[Unit]
}

trait JournalReader[F[_], E] {
  def readStream(streamId: StreamId): Stream[F, EventMessage[E]]
  def readStreamAfter(
      streamId: StreamId,
      version: EventVersion
  ): Stream[F, EventMessage[E]]
  def readStreamBefore(
      streamId: StreamId,
      version: EventVersion
  ): Stream[F, EventMessage[E]]

  def readAll: Stream[F, EventMessage[E]]
  def readAllAfter(seqNr: SeqNr): Stream[F, EventMessage[E]]
  def notifications: Stream[F, StreamId]
}

final case class EventMessage[+T](metadata: EventMetadata, payload: T)

final case class EventMetadata(
    id: UUID,
    time: OffsetDateTime,
    seqNr: SeqNr,
    version: EventVersion,
    stream: String
)
