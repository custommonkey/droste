package qq.droste

import data.Attr
import data.AttrF
import data.Coattr
import data.CoattrF
import data.Fix
import data.prelude._
import data.list._
import util.newtypes._

import cats.Eval
import cats.{Foldable, Monad}
import cats.kernel.{Monoid, Eq}
import cats.free.Trampoline
import cats.instances.function._


trait Embed[F[_], R] {
  def algebra: Algebra[F, R]
}

object Embed extends FloatingBasisInstances[Embed] {
  def apply[F[_], R](implicit ev: Embed[F, R]): Embed[F, R] = ev
}

trait Project[F[_], R] {
  def coalgebra: Coalgebra[F, R]

  def all(r: R)(p: R => Boolean)(implicit F: Foldable[F]): Boolean =
    foldMap[Boolean @@ Tags.Conjunction](r)(p(_).conjunction).unwrap

  def any(r: R)(p: R => Boolean)(implicit F: Foldable[F]): Boolean =
    foldMap[Boolean @@ Tags.Disjunction](r)(p(_).disjunction).unwrap

  def collect[U: Monoid, B]
    (r: R)
    (pf: PartialFunction[R, B])
    (implicit U: Basis[ListF[B, ?], U], F: Foldable[F])
      : U = {
    foldMap[U](r)(pf.lift(_).foldRight[U](U.algebra(NilF))((a, b) => U.algebra(ConsF(a, b))))
  }

  def contains
    (r: R, c: R)
    (implicit R: Eq[R], F: Foldable[F])
      : Boolean =
    any(r)(R.eqv(c, _))

  def foldMap[Z: Monoid]
    (r: R)
    (f: R => Z)
    (implicit F: Foldable[F])
      : Z =
    foldMapM[Trampoline, Z](r)(x => Trampoline.done(f(x))).run

  def foldMapM[M[_], Z]
    (r: R)
    (f: R => M[Z])
    (implicit M: Monad[M], Z: Monoid[Z], F: Foldable[F]): M[Z] = {
    def loop(z0: Z, term: R): M[Z] =
      M.flatMap(f(term)) { z1 =>
        F.foldLeftM(coalgebra(term), Z.combine(z0, z1))(loop(_, _))
      }

    loop(Z.empty, r)
  }
}

object Project extends FloatingBasisInstances[Project] {
  def apply[F[_], R](implicit ev: Project[F, R]): Project[F, R] = ev
}

sealed trait Basis[F[_], R]
    extends Embed[F, R]
    with Project[F, R]

object Basis extends FloatingBasisInstances[Basis] {
  def apply[F[_], R](implicit ev: Basis[F, R]): Basis[F, R] = ev
  final case class Default[F[_], R](
    algebra: Algebra[F, R],
    coalgebra: Coalgebra[F, R]) extends Basis[F, R]

  sealed trait Solve[PR[_[_]]] {
    type PatF[F[_], A]
    type PatR[F[_]] = PR[F]
  }

  object Solve extends FloatingBasisSolveInstances {
    type Aux[PR[_[_]], PF[_[_], _]] = Solve[PR] {
      type PatF[F[_], A] = PF[F, A]
    }
  }
}

private[droste] sealed trait FloatingBasisInstances[H[F[_], A] >: Basis[F, A]] extends FloatingBasisInstances0[H] {
  implicit def drosteBasisForListF[A]: H[ListF[A, ?], List[A]] =
    Basis.Default[ListF[A, ?], List[A]](ListF.toScalaListAlgebra, ListF.fromScalaListCoalgebra)

  implicit def drosteBasisForAttr[F[_], A]: H[AttrF[F, A, ?], Attr[F, A]] =
    Basis.Default[AttrF[F, A, ?], Attr[F, A]](Attr.algebra, Attr.coalgebra)

  implicit def drosteBasisForCoattr[F[_], A]: H[CoattrF[F, A, ?], Coattr[F, A]] =
    Basis.Default[CoattrF[F, A, ?], Coattr[F, A]](Coattr.algebra, Coattr.coalgebra)
}

private[droste] sealed trait FloatingBasisInstances0[H[F[_], A] >: Basis[F, A]] {
  implicit def drosteBasisForFix[F[_]]: H[F, Fix[F]] =
    Basis.Default[F, Fix[F]](Fix.algebra, Fix.coalgebra)

  implicit def drosteBasisForCatsCofree[F[_], A]: H[AttrF[F, A, ?], cats.free.Cofree[F, A]] =
    Basis.Default[AttrF[F, A, ?], cats.free.Cofree[F, A]](
      Algebra(fa => cats.free.Cofree(fa.ask, Eval.now(fa.lower))),
      Coalgebra(a => AttrF(a.head, a.tailForced)))
}

private[droste] sealed trait FloatingBasisSolveInstances {
  import Basis.Solve

  implicit val drosteSolveFix: Solve.Aux[Fix, λ[(F[_], α) => F[α]]] = null
  implicit def drosteSolveAttr[A]: Solve.Aux[Attr[?[_], A], AttrF[?[_], A, ?]] = null
  implicit def drosteSolveCatsCofree[A]: Solve.Aux[cats.free.Cofree[?[_], A], AttrF[?[_], A, ?]] = null
}
