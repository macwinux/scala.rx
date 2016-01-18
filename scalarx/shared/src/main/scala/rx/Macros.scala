package rx

import scala.language.experimental.macros
import scala.reflect.macros._

object Macros {

  def transform[T](c: Context)(src: c.Tree, newCtx: c.universe.TermName, curCtxTree: c.Tree): c.Tree = {
    import c.universe._
    object transformer extends c.universe.Transformer {
      override def transform(tree: c.Tree): c.Tree = {
        if (curCtxTree.isEmpty) q"$newCtx"
        else if (tree.tpe =:= c.weakTypeOf[rx.RxCtx.CompileTime.type]) q"$newCtx"
        else if (tree.equalsStructure(curCtxTree)) q"$newCtx"
        else if (tree.tpe =:= c.weakTypeOf[rx.RxCtx.Unsafe.type]) q"$newCtx"
        else super.transform(tree)
      }
    }
    transformer.transform(src)
  }

  def ensureStaticEnclosingOwners(c: Context)(chk: c.Symbol, abortOnFail: Boolean): Boolean = {
    import c.universe._
    //Failed due to an enclosing trait or abstract class
    if(chk.isAbstract && chk.isClass) {
      val msg = s"This Rx might leak! Either explicitly mark it unsafe (Rx.unsafe) or ensure an implicit RxCtx is in scope!"
      if(abortOnFail) c.abort(c.enclosingPosition,msg)
      else false
    }
    //Failed due to an enclosing method or class
    else if((chk.isMethod && !(chk.isMethod && chk.isTerm && chk.asTerm.isLazy)) || (chk.isClass && !chk.isModuleClass)) {
      val msg =s"""
        |This Rx might leak! Either explicitly mark it unsafe (Rx.unsafe) or make an implicit RxCtx available
        |in the enclosing scope, for example, by adding (implicit ctx: RxCtx) to line ${chk.pos.line}: $chk
        |""".stripMargin
      if(abortOnFail) c.abort(c.enclosingPosition, msg)
      else false
    }
    else if(chk.owner == NoSymbol) true
    else ensureStaticEnclosingOwners(c)(chk.owner, abortOnFail)
  }

  def buildSafeCtx(c: Context)(): c.Expr[RxCtx] = {
    import c.universe._

    val inferredCtx = c.inferImplicitValue(c.weakTypeOf[rx.RxCtx], withMacrosDisabled = true)

    val isCompileTimeCtx = inferredCtx.isEmpty || inferredCtx.tpe =:= c.weakTypeOf[rx.RxCtx.CompileTime.type]

    if(isCompileTimeCtx)
      ensureStaticEnclosingOwners(c)(c.internal.enclosingOwner, abortOnFail = true)

    val safeCtx =
      if(isCompileTimeCtx) c.Expr[RxCtx](q"rx.RxCtx.Unsafe")
      else if(c.internal.enclosingOwner.fullName == inferredCtx.symbol.fullName) c.Expr[RxCtx](q"rx.RxCtx.Unsafe")
      else c.Expr[RxCtx](q"$inferredCtx")

    safeCtx
  }

  def encCtx(c: Context)(ctx: c.Expr[RxCtx]) = {
    import c.universe._
    val isCompileTimeCtx = ctx.tree.tpe =:= c.weakTypeOf[rx.RxCtx.CompileTime.type]

    if(isCompileTimeCtx)
      ensureStaticEnclosingOwners(c)(c.internal.enclosingOwner, abortOnFail = true)

    val enclosingCtx =
      if(isCompileTimeCtx) c.Expr[RxCtx](q"rx.RxCtx.Unsafe")
      else ctx

    enclosingCtx
  }

  def getDownstream[T: c.WeakTypeTag](c: Context)(node: c.Tree): c.Tree = {
    import c.universe._
    def rec(base: c.Tree, tpe: c.Type): c.Tree = {
      if (tpe <:< c.weakTypeOf[rx.Node[_]]) {
        val innerType = tpe.typeArgs.head
        q"$base.node :: ${rec(q"$base.node.now", innerType)}"
      } else q"$base.node :: Nil"
    }
    c.resetLocalAttrs(rec(node, c.weakTypeOf[T]))
  }

  def addDownstreamOfAll[T: c.WeakTypeTag](c: Context)(node: c.Expr[rx.Node[T]])(ctx: c.Expr[RxCtx]): c.Expr[Unit] = {
    import c.universe._
    val next = if(c.weakTypeOf[T] <:< c.weakTypeOf[rx.Node[_]]) {
      val innerType = c.weakTypeTag[T].tpe.typeArgs.head
      q"rx.Node.addDownstreamOfAll[$innerType]($node.now)($ctx)"
    } else {
      q"()"
    }
    val res = q"{$node.Internal.addDownstream($ctx); $next }"
    c.Expr[Unit](c.resetLocalAttrs(res))
  }

  def duplicate[T: c.WeakTypeTag](c: Context)(node: c.Expr[rx.Var[T]])(ctx: c.Expr[RxCtx]): c.Expr[rx.Var[T]] = {
    import c.universe._
    val inner = if(c.weakTypeOf[T] <:< c.weakTypeOf[rx.Var[_]]) {
      val innerType = c.weakTypeTag[T].tpe.typeArgs.head
      q"rx.Var.duplicate[$innerType]($node.now)($ctx)"
    } else {
      q"$node.now"
    }
    val res = q"rx.Var($inner)"
    c.Expr[rx.Var[T]](c.resetLocalAttrs(res))
  }

  def mapped[T: c.WeakTypeTag, V: c.WeakTypeTag, Out: c.WeakTypeTag](c: Context)(f: c.Expr[T => V])(ctx: c.Expr[rx.RxCtx]): c.Expr[Rx[Out]] = {
    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tryTpe = c.weakTypeOf[scala.util.Try[_]]
    val tPrefix = transform(c)(c.prefix.tree,newCtx,ctx.tree)
    val call = if(c.weakTypeOf[T] <:< tryTpe && c.weakTypeOf[V] <:< tryTpe) {
      q"${transform(c)(f.tree,newCtx,ctx.tree)}($tPrefix.node.toTry).get"
    } else {
      q"${transform(c)(f.tree,newCtx,ctx.tree)}($tPrefix.node.now)"
    }
    val res = c.Expr[Rx[Out]](
      q"""rx.Rx.build { implicit $newCtx: rx.RxCtx =>
          rx.Node.addDownstreamOfAll($tPrefix.node)($newCtx)
          $call
        }(${encCtx(c)(ctx)})
      """)
    c.Expr[rx.Rx[Out]](c.resetLocalAttrs(res.tree))
  }

  def flatMapped[T: c.WeakTypeTag, V: c.WeakTypeTag](c: Context)(f: c.Expr[T => Rx[V]])(ctx: c.Expr[rx.RxCtx]): c.Expr[Rx[V]] = {
    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tryTpe = c.weakTypeOf[scala.util.Try[_]]
    val tPrefix = transform(c)(c.prefix.tree,newCtx,ctx.tree)
    val call = if(c.weakTypeOf[T] <:< tryTpe) {
      q"${transform(c)(f.tree,newCtx,ctx.tree)}($tPrefix.node.toTry)"
    } else {
      q"${transform(c)(f.tree,newCtx,ctx.tree)}($tPrefix.node.now)"
    }
    val res = c.Expr[Rx[V]](
      q"""rx.Rx.build { $newCtx: rx.RxCtx =>
          rx.Node.addDownstreamOfAll($tPrefix.node)($newCtx)
          $call()($newCtx)
        }(${encCtx(c)(ctx)})
      """)
    c.Expr[rx.Rx[V]](c.resetLocalAttrs(res.tree))
  }

  def filtered[In: c.WeakTypeTag, T: c.WeakTypeTag](c: Context)(f: c.Expr[In => Boolean])(ctx: c.Expr[rx.RxCtx]): c.Expr[Rx[T]] = {
    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tPrefix = transform(c)(c.prefix.tree,newCtx,ctx.tree)
    val tTpe = c.weakTypeOf[T]
    def isHigher = c.weakTypeOf[In] <:< c.weakTypeOf[rx.Var[_]]
    val isSafe = c.prefix.tree.tpe <:< c.weakTypeOf[SafeOps[_]]
    val initValue = (isHigher, isSafe) match{
      case (true, false) => q"rx.Var.duplicate($tPrefix.node.now)($newCtx)"
      case (true, true) => q"$tPrefix.node.toTry.map(rx.Var.duplicate(_)($newCtx))"
      case (false, false) => q"$tPrefix.node.now"
      case (false, true) => q"$tPrefix.node.toTry"
    }

    val checkFunc = q"${transform(c)(f.tree,newCtx,ctx.tree)}"

    val res = c.Expr[Rx[T]](q"""
      ${c.prefix}.macroImpls.filterImpl(
        ($newCtx: RxCtx) => $initValue,
        ${c.prefix}.node,
        ($newCtx: RxCtx) => $tPrefix.node
      )(
        ($newCtx: RxCtx) => $checkFunc,
        ($newCtx: RxCtx) => rx.Node.getDownstream($tPrefix.node),
        ($newCtx: RxCtx) => ${encCtx(c)(ctx)},
        ${c.prefix}.macroImpls.get,
        ${c.prefix}.macroImpls.unwrap,
        $ctx
      )
    """)
    c.Expr[rx.Rx[T]](c.resetLocalAttrs(res.tree))
  }

  type Id[T] = T

  def folded[T: c.WeakTypeTag, V: c.WeakTypeTag, Wrap[_]]
            (c: Context)
            (start: c.Expr[V])
            (f: c.Expr[(V,T) => V])
            (ctx: c.Expr[rx.RxCtx])
            (implicit w: c.WeakTypeTag[Wrap[_]]): c.Expr[Rx[T]] = {

    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tPrefix = transform(c)(c.prefix.tree, newCtx, ctx.tree)
    val foldFunc = transform(c)(f.tree, newCtx, ctx.tree)

    val res = c.Expr[Rx[T]](c.resetLocalAttrs(q"""
      ${c.prefix}.macroImpls.foldImpl(
        ($newCtx: RxCtx) => $start,
        ($newCtx: RxCtx) => $tPrefix.node
      )(
        ($newCtx: RxCtx) => $foldFunc,
        ($newCtx: RxCtx) => rx.Node.getDownstream($tPrefix.node),
        ${encCtx(c)(ctx)},
        ${c.prefix}.macroImpls.get,
        ${c.prefix}.macroImpls.unwrap,
        ${c.prefix}.macroImpls.unwrap
      )
    """))
    res
  }

  class Operator[Wrap[_]]{
    def foldImpl[T, V](start: RxCtx => Wrap[V],
                       tPrefix: RxCtx => Node[T])
                      (f: RxCtx => (Wrap[V], Wrap[T]) => Wrap[V],
                       downStream: RxCtx => Seq[Node[_]],
                       enclosing: RxCtx,
                       toT: Node[T] => Wrap[T],
                       toOut: Wrap[V] => V,
                       toOut2: Wrap[T] => T): Rx[V] = {

      var prev: Wrap[V] = start(enclosing)
      rx.Rx.build { newCtx: rx.RxCtx =>
        downStream(newCtx).foreach(_.Internal.addDownstream(newCtx))
        prev = f(newCtx)(prev, toT(tPrefix(newCtx)))
        toOut(prev)
      }(enclosing)
    }

    /**
      * Split into two to make type-inference work
      */
    def reducedImpl[T](initValue: RxCtx => Wrap[T],
                        tPrefix: Node[T],
                        prefix: Node[T])
                       (reduceFunc: (Wrap[T], Wrap[T]) => Wrap[T],
                        toT: Node[T] => Wrap[T],
                        toOut: Wrap[T] => T,
                        enclosing: RxCtx,
                        downStream: Seq[Node[_]]): Rx[T] = {
      var init = true
      def getPrev = toT(prefix)

      var prev = getPrev

      def next: T = toOut(prev)

      rx.Rx.build { newCtx: rx.RxCtx =>
        downStream.foreach(_.Internal.addDownstream(newCtx))
        if(init) {
          init = false
          prev = initValue(newCtx)
          next
        } else {
          prev = reduceFunc(prev, getPrev)
          next
        }
      }(enclosing)
    }

    def filterImpl[T, Out](start: RxCtx => T,
                           prefix: Node[Out],
                           tPrefix: RxCtx => Node[Out])
                          (f: RxCtx => T => Boolean,
                           downStream: RxCtx => Seq[Node[_]],
                           enclosing: RxCtx => RxCtx,
                           toT: Node[Out] => T,
                           toOut: T => Out,
                           ctx: RxCtx) = {

      var init = true
      var prev = toT(prefix)
      rx.Rx.build { newCtx: rx.RxCtx =>
        downStream(newCtx).foreach(_.Internal.addDownstream(newCtx))
        if(f(newCtx)(toT(tPrefix(newCtx))) || init) {
          init = false
          prev = start(newCtx)
        }
        toOut(prev)
      }(enclosing(ctx))
    }

  }

  def reduced[T: c.WeakTypeTag, Wrap[_]]
             (c: Context)
             (f: c.Expr[(Wrap[T], Wrap[T]) => Wrap[T]])
             (ctx: c.Expr[rx.RxCtx])
             (implicit w: c.WeakTypeTag[Wrap[_]]): c.Expr[Rx[T]] = {
    import c.universe._
    val newCtx =  c.fresh[TermName]("rxctx")
    val tPrefix = transform(c)(c.prefix.tree,newCtx,ctx.tree)
    val isSafe = c.prefix.tree.tpe <:< c.weakTypeOf[SafeOps[_]]
    val isHigher = c.weakTypeOf[T] <:< c.weakTypeOf[rx.Var[_]]

    val reduceFunc = transform(c)(f.tree,newCtx,ctx.tree)

    val initValue = (isHigher, isSafe) match{
      case (true, false) => q"rx.Var.duplicate($tPrefix.node.now)($newCtx)"
      case (true, true) => q"$tPrefix.node.toTry.map(rx.Var.duplicate(_)($newCtx))"
      case (false, false) => q"$tPrefix.node.now"
      case (false, true) => q"$tPrefix.node.toTry"
    }


    val res = c.Expr[Rx[T]](q"""
      ${c.prefix}.macroImpls.reducedImpl[${weakTypeOf[T]}](
        ($newCtx: RxCtx) => $initValue,
        $tPrefix.node,
        ${c.prefix}.node
      )(
        $reduceFunc,
        ${if (isSafe) q"_.toTry" else q"_.now"},
        ${if (isSafe) q"_.get" else q"(x => x)"},
        ${encCtx(c)(ctx)},
        rx.Node.getDownstream($tPrefix.node)
      )
    """)

    c.Expr[rx.Rx[T]](c.resetLocalAttrs(res.tree))
  }

  def buildMacro[T: c.WeakTypeTag](c: Context)(func: c.Expr[T])(curCtx: c.Expr[rx.RxCtx]): c.Expr[Rx[T]] = {
    import c.universe._

    val newCtx =  c.fresh[TermName]("rxctx")

    val isCompileTimeCtx = curCtx.tree.tpe =:= c.weakTypeOf[rx.RxCtx.CompileTime.type]

    if(isCompileTimeCtx)
      ensureStaticEnclosingOwners(c)(c.internal.enclosingOwner, abortOnFail = true)

    val enclosingCtx =
      if(isCompileTimeCtx) c.Expr[RxCtx](q"rx.RxCtx.Unsafe")
      else curCtx

    val res = q"rx.Rx.build{$newCtx: rx.RxCtx => ${transform(c)(func.tree, newCtx, curCtx.tree)}}($enclosingCtx)"
    c.Expr[Rx[T]](c.resetLocalAttrs(res))
  }

  def buildUnsafe[T: c.WeakTypeTag](c: Context)(func: c.Expr[T]): c.Expr[Rx[T]] = {
    import c.universe._

    val inferredCtx = c.inferImplicitValue(c.weakTypeOf[rx.RxCtx])

    require(!inferredCtx.isEmpty)

    val newCtx = c.fresh[TermName]("rxctx")

    val enclosingCtx =
      if(inferredCtx.tpe =:= c.weakTypeOf[rx.RxCtx.CompileTime.type]) c.Expr[RxCtx](q"rx.RxCtx.Unsafe")
      else if(inferredCtx.isEmpty) c.Expr[RxCtx](q"rx.RxCtx.Unsafe")
      else c.Expr[RxCtx](q"$inferredCtx")

    val res = q"rx.Rx.build{$newCtx: rx.RxCtx => ${transform(c)(func.tree, newCtx, inferredCtx)}}($enclosingCtx)"
    c.Expr[Rx[T]](c.resetLocalAttrs(res))
  }

  def buildImplicitRxCtx(c: Context): c.Expr[RxCtx] = {
    import c.universe._
    val inferredCtx = c.inferImplicitValue(c.weakTypeOf[rx.RxCtx], withMacrosDisabled = true)
    val isCompileTime = inferredCtx.isEmpty
    val staticContext = ensureStaticEnclosingOwners(c)(c.internal.enclosingOwner, abortOnFail = false)
    val implicitCtx =
      if(isCompileTime && staticContext) q"rx.RxCtx.Unsafe"
      else if(isCompileTime && !staticContext) q"rx.RxCtx.CompileTime"
      else q"$inferredCtx"
    c.Expr[RxCtx](c.resetLocalAttrs(implicitCtx))
  }
}
