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

package edomata.core

import cats.Applicative
import cats.Bifunctor
import cats.Eval
import cats.MonadError
import cats.Traverse
import cats.data.Chain
import cats.data.EitherNec
import cats.data.NonEmptyChain
import cats.data.Validated
import cats.data.ValidatedNec
import cats.kernel.Eq

import scala.annotation.tailrec

import Decision._

/** Represents states in a decision context */
sealed trait Decision[+R, +E, +A] extends Product with Serializable { self =>
  def map[B](f: A => B): Decision[R, E, B] =
    self match {
      case InDecisive(t)     => InDecisive(f(t))
      case Accepted(evs, a)  => Accepted(evs, f(a))
      case Rejected(reasons) => Rejected(reasons)
    }

  def flatMap[R2 >: R, E2 >: E, B](
      f: A => Decision[R2, E2, B]
  ): Decision[R2, E2, B] =
    self match {
      case Accepted(events, result) =>
        f(result) match {
          case Accepted(events2, result) =>
            Accepted(events ++ events2, result)
          case InDecisive(result) => Accepted(events, result)
          case other              => other
        }
      case InDecisive(result) => f(result)
      case Rejected(reasons)  => Rejected(reasons)
    }

  def isRejected: Boolean = self match {
    case Rejected(_) => true
    case _           => false
  }

  def isAccepted: Boolean = self match {
    case Accepted(_, _) => true
    case _              => false
  }

  def visit[B](fr: NonEmptyChain[R] => B, fa: A => B): B = self match {
    case Decision.InDecisive(a)  => fa(a)
    case Decision.Accepted(_, a) => fa(a)
    case Decision.Rejected(r)    => fr(r)
  }

  def toValidated: ValidatedNec[R, A] = self match {
    case Decision.Accepted(_, t) => Validated.Valid(t)
    case Decision.InDecisive(t)  => Validated.Valid(t)
    case Decision.Rejected(r)    => Validated.Invalid(r)
  }
  def toOption: Option[A] = visit(_ => None, Some(_))
  def toEither: EitherNec[R, A] = visit(Left(_), Right(_))

}

object Decision extends DecisionConstructors, DecisionCatsInstances0 {
  final case class InDecisive[T](result: T)
      extends Decision[Nothing, Nothing, T]
  final case class Accepted[E, T](events: NonEmptyChain[E], result: T)
      extends Decision[Nothing, E, T]
  final case class Rejected[R](reasons: NonEmptyChain[R])
      extends Decision[R, Nothing, Nothing]
}

sealed trait DecisionConstructors {
  def pure[R, E, T](t: T): Decision[R, E, T] = InDecisive(t)

  def unit[R, E]: Decision[R, E, Unit] = InDecisive(())

  def accept[R, E](ev: E, evs: E*): Decision[R, E, Unit] =
    acceptReturn(())(ev, evs: _*)

  def acceptReturn[R, E, T](t: T)(ev: E, evs: E*): Decision[R, E, T] =
    Accepted(NonEmptyChain.of(ev, evs: _*), t)

  def reject[R, E](
      reason: R,
      otherReasons: R*
  ): Decision[R, E, Nothing] =
    Rejected(NonEmptyChain.of(reason, otherReasons: _*))

  def validate[R, E, T](
      validation: ValidatedNec[R, T]
  ): Decision[R, E, T] =
    validation match {
      case Validated.Invalid(e) => Rejected(e)
      case Validated.Valid(a)   => InDecisive(a)
    }
}

type D[R, E] = [T] =>> Decision[R, E, T]

sealed trait DecisionCatsInstances0 extends DecisionCatsInstances1 {
  given [R, E]: MonadError[D[R, E], NonEmptyChain[R]] =
    new MonadError[D[R, E], NonEmptyChain[R]] {
      override def pure[A](x: A): Decision[R, E, A] = Decision.pure(x)

      override def map[A, B](
          fa: Decision[R, E, A]
      )(f: A => B): Decision[R, E, B] =
        fa.map(f)

      override def flatMap[A, B](fa: Decision[R, E, A])(
          f: A => Decision[R, E, B]
      ): Decision[R, E, B] =
        fa.flatMap(f)

      @tailrec
      private def step[A, B](a: A, evs: Chain[E] = Chain.empty)(
          f: A => Decision[R, E, Either[A, B]]
      ): Decision[R, E, B] =
        f(a) match {
          case Decision.Accepted(ev2, Left(a)) =>
            step(a, evs ++ ev2.toChain)(f)
          case Decision.Accepted(ev2, Right(b)) =>
            Decision.Accepted(ev2.prependChain(evs), b)
          case Decision.InDecisive(Left(a)) => step(a, evs)(f)
          case Decision.InDecisive(Right(b)) =>
            NonEmptyChain
              .fromChain(evs)
              .fold(Decision.InDecisive(b))(Decision.Accepted(_, b))
          case Decision.Rejected(rs) => Decision.Rejected(rs)
        }

      override def tailRecM[A, B](a: A)(
          f: A => Decision[R, E, Either[A, B]]
      ): Decision[R, E, B] =
        step(a)(f)

      def handleErrorWith[A](
          fa: Decision[R, E, A]
      )(f: NonEmptyChain[R] => Decision[R, E, A]): Decision[R, E, A] =
        fa match {
          case Decision.Rejected(e) => f(e)
          case other                => other
        }

      def raiseError[A](e: NonEmptyChain[R]): Decision[R, E, A] =
        Decision.Rejected(e)

    }

  given [R, E, T]: Eq[Decision[R, E, T]] = Eq.instance(_ == _)
}

sealed trait DecisionCatsInstances1 {

  given [R, E]: Traverse[D[R, E]] = new Traverse {
    def traverse[G[_]: Applicative, A, B](fa: Decision[R, E, A])(
        f: A => G[B]
    ): G[Decision[R, E, B]] = fa.visit(
      rej => Applicative[G].pure(Decision.Rejected(rej)),
      a => Applicative[G].map(f(a))(b => fa.map(_ => b))
    )

    def foldLeft[A, B](fa: Decision[R, E, A], b: B)(f: (B, A) => B): B =
      fa.visit(_ => b, a => f(b, a))

    def foldRight[A, B](fa: Decision[R, E, A], lb: Eval[B])(
        f: (A, Eval[B]) => Eval[B]
    ): Eval[B] =
      fa.visit(_ => lb, a => f(a, lb))
  }
}

private[edomata] transparent trait ValidatedSyntax {
  extension [R, T](self: ValidatedNec[R, T]) {
    def toDecision[E]: Decision[R, E, T] = self match {
      case Validated.Valid(t)   => Decision.InDecisive(t)
      case Validated.Invalid(r) => Decision.Rejected(r)
    }
  }
}
