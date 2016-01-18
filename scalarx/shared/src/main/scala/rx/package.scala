import rx.Macros.Id

import scala.util.Try

/**
 * Created by haoyi on 12/13/14.
 */
package object rx {

  sealed trait OpsContext[Wrap[_]] extends Macros.Operator[Wrap]{
    def get[T](t: Node[T]): Wrap[T]
    def unwrap[T](t: Wrap[T]): T
  }



  /**
   * All [[Node]]s have a set of operations you can perform on them, e.g. `map` or `filter`
   */
  implicit class GenericOps[T](val node: Node[T]) extends AnyVal {
    import scala.language.experimental.macros

    def macroImpls = new OpsContext[Id] {
      def get[T](t: Node[T]) = t.now
      def unwrap[T](t: T) = t
    }
    def map[V](f: T => V)(implicit ctx: RxCtx): Rx[V] = macro Macros.mapped[T, V, V]

    def flatMap[V](f: T => Rx[V])(implicit ctx: RxCtx): Rx[V] = macro Macros.flatMapped[T,V]

    def filter(f: T => Boolean)(implicit ctx: RxCtx): Rx[T] = macro Macros.filtered[T,T]

    def fold[V](start: V)(f: ((Id[V], Id[T]) => Id[V]))(implicit ctx: RxCtx): Rx[V] = macro Macros.folded[T, V, Id]

    def reduce(f: (Id[T], Id[T]) => Id[T])(implicit ctx: RxCtx): Rx[T] = macro Macros.reduced[T, Id]

    def foreach(f: T => Unit) = node.trigger(f(node.now))
  }

  abstract class SafeOps[T](val node: Rx[T]) {
    import scala.language.experimental.macros
    def macroImpls = new OpsContext[util.Try] {
      def get[T](t: Node[T]) = t.toTry
      def unwrap[T](t: Try[T]) = t.get
    }
    def map[V](f: Try[T] => Try[V])(implicit ctx: RxCtx): Rx[V] = macro Macros.mapped[Try[T],Try[V], V]

    def flatMap[V](f: Try[T] => Rx[V])(implicit ctx: RxCtx): Rx[V] = macro Macros.flatMapped[Try[T],V]

    def filter(f: Try[T] => Boolean)(implicit ctx: RxCtx): Rx[T] = macro Macros.filtered[Try[T],T]

    def fold[V](start: Try[V])(f: (Try[V], Try[T]) => Try[V])(implicit ctx: RxCtx): Rx[V] = macro Macros.folded[T, V, Try]

    def reduce(f: (Try[T], Try[T]) => Try[T])(implicit ctx: RxCtx): Rx[T] = macro Macros.reduced[T, Try]

    def foreach(f: T => Unit) = node.trigger(node.toTry.foreach(f))
  }

  /**
   * All [[Rx]]s have a set of operations you can perform on them via `myRx.all.*`,
   * which lifts the operation to working on a `Try[T]` rather than plain `T`s
   */
  implicit class RxPlusOps[T](val r: Rx[T]) {
    object all extends SafeOps[T](r)
  }

}