package edomata.core

import cats.*
import cats.data.*
import cats.implicits.*
import munit.*
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.MonadTests
import cats.laws.discipline.TraverseTests
import cats.laws.discipline.arbitrary.catsLawsArbitraryForNonEmptyChain
import cats.laws.discipline.arbitrary.catsLawsCogenForNonEmptyChain

abstract class ResponseTLaws[Res[+_, +_], Rejection, Out, Notification](
    rejected: Gen[Res[Rejection, Out]],
    notRejected: Gen[Res[Rejection, Out]]
)(using
    E: RaiseError[Res]
)(using
    MonadError[Res[Rejection, *], NonEmptyChain[Rejection]],
    // Traverse[Res[Rejection, *]],
    Arbitrary[Notification]
) extends DisciplineSuite {
  private val anySut = Gen.oneOf(rejected, notRejected)

  private val notifications: Gen[Chain[Notification]] = Gen
    .containerOf[Seq, Notification](Arbitrary.arbitrary[Notification])
    .map(Chain.fromSeq)
  type App[T] = ResponseT[Res, Rejection, Notification, T]

  def ResponseD[T](
      d: Res[Rejection, T],
      ns: Chain[Notification]
  ): App[T] = ResponseT(d, ns)

  protected given [T: Arbitrary]: Arbitrary[App[T]] = Arbitrary(
    for {
      n <- notifications
      d <- anySut
      t <- Arbitrary.arbitrary[T]
    } yield ResponseD(d.as(t), n)
  )

  protected def otherLaws(rules: Laws#RuleSet*) = (Seq(
    MonadTests[App].monad[Int, Int, String],
    EqTests[App[Long]].eqv
  ) ++ rules).foreach(checkAll("laws", _))

  property("Accumulates on accept") {
    forAll(notRejected, notifications, notRejected, notifications) {
      (a1, n1, a2, n2) =>
        val r1 = ResponseD(a1, n1)
        val r2 = ResponseD(a2, n2)
        val r3 = r1 >> r2
        assertEquals(r3, ResponseD(a1.flatMap(_ => a2), n1 ++ n2))
    }
  }

  property("Resets on rejection") {
    forAll(notRejected, notifications, rejected, notifications) {
      (a1, n1, a2, n2) =>
        val r1 = ResponseD(a1, n1)
        val r2 = ResponseD(a2, n2)
        val r3 = r1 >> r2
        assertEquals(r3, ResponseD(a1.flatMap(_ => a2), n2))
        assert(E.isError(r3.result))
    }
  }
  property("Rejected does not change") {
    forAll(rejected, notifications, anySut, notifications) { (a1, n1, a2, n2) =>
      val r1 = ResponseD(a1, n1)
      val r2 = ResponseD(a2, n2)
      val r3 = r1 >> r2
      assertEquals(r3, r1)
      assert(E.isError(r3.result))
    }
  }
  property("Publish on rejection") {
    forAll(rejected, notifications, notifications) { (a1, n1, n2) =>
      val r1 = ResponseD(a1, n1)
      val r2 = r1.publishOnRejection(n2.toList: _*)

      assertEquals(
        r2,
        r1.copy(notifications = r1.notifications ++ n2)
      )
    }
  }
  property("Publish adds notifications") {
    forAll(anySut, notifications, notifications) { (a1, n1, n2) =>
      val r1 = ResponseD(a1, n1)
      val r2 = r1.publish(n2.toList: _*)
      assertEquals(r2, r1.copy(notifications = n1 ++ n2))
    }
  }
  property("Reset clears notifications") {
    forAll(anySut, notifications) { (a1, n1) =>
      val r1 = ResponseD(a1, n1)
      val r2 = r1.reset
      assertEquals(r2.notifications, Chain.nil)
      assertEquals(r2.result, r1.result)
    }
  }

}