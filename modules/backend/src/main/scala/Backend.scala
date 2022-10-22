/*
 * Copyright 2021 Hossein Naderi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edomata.backend

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import edomata.core.*

import scala.concurrent.duration.*

trait Backend[F[_], S, E, R, N] {
  def compile: CommandHandler[F, S, E, R, N]
  def outbox: OutboxReader[F, N]
  def journal: JournalReader[F, E]
  def repository: RepositoryReader[F, S, E, R]
  def updates: NotificationsConsumer[F]
}

object Backend {
  final class Builder[F[_]: Async, Codec[_], C, S, E, R, N] private[Backend] (
      driver: Resource[F, StorageDriver[F, Codec]],
      domain: Domain[C, S, E, R, N],
      snapshot: StorageDriver[F, Codec] => Resource[F, SnapshotStore[F, S]],
      val maxRetry: Int = 5,
      val retryInitialDelay: FiniteDuration = 2.seconds,
      val cached: Boolean = true,
      val commandCacheSize: Int = 100
  )(using ModelTC[S, E, R]) {
    private def copy(
        driver: Resource[F, StorageDriver[F, Codec]] = driver,
        domain: Domain[C, S, E, R, N] = domain,
        snapshot: StorageDriver[F, Codec] => Resource[F, SnapshotStore[F, S]] =
          snapshot,
        maxRetry: Int = maxRetry,
        retryInitialDelay: FiniteDuration = retryInitialDelay,
        cached: Boolean = cached,
        commandCacheSize: Int = commandCacheSize
    ) = new Builder(
      driver = driver,
      domain = domain,
      snapshot = snapshot,
      maxRetry = maxRetry,
      retryInitialDelay = retryInitialDelay,
      cached = cached,
      commandCacheSize = commandCacheSize
    )

    def persistedSnapshot(
        maxInMem: Int = 1000,
        maxBuffer: Int = 100,
        maxWait: FiniteDuration = 1.minute
    )(using codec: Codec[S]): Builder[F, Codec, C, S, E, R, N] =
      copy(snapshot =
        _.snapshot
          .flatMap(store =>
            SnapshotStore
              .persisted(
                store,
                size = maxInMem,
                maxBuffer = maxBuffer,
                maxWait
              )
          )
      )

    def disableCache: Builder[F, Codec, C, S, E, R, N] = copy(cached = false)

    def inMemSnapshot(
        maxInMem: Int = 1000
    ): Builder[F, Codec, C, S, E, R, N] =
      copy(snapshot = _ => Resource.eval(SnapshotStore.inMem(maxInMem)))

    def withSnapshot(
        s: Resource[F, SnapshotStore[F, S]]
    ): Builder[F, Codec, C, S, E, R, N] = copy(snapshot = _ => s)

    def withRetryConfig(
        maxRetry: Int = maxRetry,
        retryInitialDelay: FiniteDuration = retryInitialDelay
    ): Builder[F, Codec, C, S, E, R, N] =
      copy(maxRetry = maxRetry, retryInitialDelay = retryInitialDelay)

    def withCommandCacheSize(
        maxCommandsToCache: Int
    ): Builder[F, Codec, C, S, E, R, N] =
      copy(commandCacheSize = maxCommandsToCache)

    def build(using
        event: Codec[E],
        notifs: Codec[N]
    ): Resource[F, Backend[F, S, E, R, N]] = for {
      dr <- driver
      s <- snapshot(dr)
      storage <- dr.build[S, E, R, N](s)
      compiler <-
        if cached then
          Resource
            .eval(CommandStore.inMem(commandCacheSize))
            .map(CachedRepository(storage.repository, _, s))
        else Resource.pure(storage.repository)
      h = CommandHandler.withRetry(compiler, maxRetry, retryInitialDelay)

    } yield BackendImpl(
      h,
      storage.outbox,
      storage.journal,
      storage.reader,
      storage.updates
    )
  }

  final class PartialBuilder[C, S, E, R, N](
      domain: Domain[C, S, E, R, N]
  )(using model: ModelTC[S, E, R]) {
    def use[F[_]: Async, Codec[_]](
        driver: StorageDriver[F, Codec]
    ): Builder[F, Codec, C, S, E, R, N] =
      from(Resource.pure(driver))

    def use[F[_]: Async, Codec[_], D <: StorageDriver[F, Codec]](
        driver: F[D]
    ): Builder[F, Codec, C, S, E, R, N] =
      from(Resource.eval(driver))

    def from[F[_]: Async, Codec[_], D <: StorageDriver[F, Codec]](
        driver: Resource[F, D]
    ): Builder[F, Codec, C, S, E, R, N] =
      new Builder(driver, domain, _ => Resource.eval(SnapshotStore.inMem(1000)))
  }

  def builder[C, S, E, R, N](
      domain: Domain[C, S, E, R, N]
  )(using
      model: ModelTC[S, E, R]
  ): PartialBuilder[C, S, E, R, N] = new PartialBuilder(domain)
  def builder[C, S, E, R, N](
      service: edomata.core.DomainModel[S, E, R]#Service[C, N]
  )(using
      model: ModelTC[S, E, R]
  ): PartialBuilder[C, S, E, R, N] = new PartialBuilder(service.domain)
}

private[edomata] final case class BackendImpl[F[_], S, E, R, N](
    compile: CommandHandler[F, S, E, R, N],
    outbox: OutboxReader[F, N],
    journal: JournalReader[F, E],
    repository: RepositoryReader[F, S, E, R],
    updates: NotificationsConsumer[F]
) extends Backend[F, S, E, R, N]
