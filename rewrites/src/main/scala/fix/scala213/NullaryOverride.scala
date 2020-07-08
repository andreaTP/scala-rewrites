package fix.scala213

import java.io.{File, FileWriter}
import java.nio.file.{Path, Paths}

import scala.PartialFunction.cond
import scala.annotation.tailrec
import scala.util.Using

import metaconfig.Configured
import scalafix.internal.rule.CompilerException
import scalafix.internal.v1.LazyValue
import scalafix.v1._

import scala.meta._
import scala.meta.internal.pc.ScalafixGlobal
import scala.meta.tokens.Token.{LeftParen, RightParen}

import NullaryOverride._
import NullaryOverrideMode._

class NullaryOverride(
    config: NullaryOverrideConfig,
    global: LazyValue[ScalafixGlobal]
) extends impl.CompilerDependentRule(global, "fix.scala213.NullaryOverride") {
  def this() = this(NullaryOverrideConfig.default, LazyValue.later(() => ScalafixGlobal.newCompiler(Nil, Nil, Map.empty)))

  override def withConfiguration(config: Configuration): Configured[NullaryOverride] = {
    val newGlobal = LazyValue.later { () =>
      ScalafixGlobal.newCompiler(config.scalacClasspath, config.scalacOptions, Map.empty)
    }
    config.conf
      .getOrElse("NullaryOverride")(NullaryOverrideConfig.default)
      .map(new NullaryOverride(_, newGlobal))
  }

  override def afterComplete(): Unit = {
    config.saveCollected()
    super.afterComplete()
  }

  protected def unsafeFix()(implicit doc: SemanticDocument): Patch = {
    val power = new Power(global.value)
    doc.tree.collect(collector(power, config)).asPatch
  }
}

/** @note When remove `()` from `def foo()` in type T then
  *       all references `t.foo()` must be rewritten to `t.foo` (not just in this `doc`)
  *       Similar, `t.foo _` must be rewritten to `() => t.foo` */
object NullaryOverride {
  def collector(
       power: => IPower,
       config: NullaryOverrideConfig
  )(implicit doc: SemanticDocument): PartialFunction[Tree, Patch] = config.mode match {
    case ResetAndCollect | CollectAppend => {
      case Defn.Def(_, name, _, Nil, _, _) if power.isNullaryMethod(name).contains(false) =>
        config.nonNullarySymbols += name.symbol.value
        Patch.empty
      case Defn.Def(_, name, _, List(Nil), _, _) if power.isNullaryMethod(name).contains(true) =>
        config.nullarySymbols += name.symbol.value
        Patch.empty
    }
    case Rewrite => {
      case config.nonNullaryMatcher(name: Term.Name)
        if name.isReference && !isApply(name) =>
        Patch.addRight(name, "()")
      case config.nonNullaryMatcher(Defn.Def(_, name, _, Nil, _, _)) =>
        Patch.addRight(name, "()")
      case config.nullaryMatcher(t: Defn.Def) =>
        removeParens(t, t.name)
      case config.nullaryMatcher(t@Term.Apply(fun, _)) =>
        removeParens(t, fun)
    }
  }

  private def isApply(name: Term.Name): Boolean = name.parent match {
    case Some(_: Term.Apply) => true
    case Some(s @ Term.Select(_, `name`)) => s.parent.exists(_.isInstanceOf[Term.Apply])
    case _ => false
  }

  private def removeParens(t: Tree, name: Term) = {
    val lastNameTok = name.tokens.last
    val tail = t.tokens.dropWhile(_ != lastNameTok)
    // '(' and ')' and all trivial token between those parens
    val parens = tail.slice(
      tail.indexWhere(_.is[LeftParen]),
      tail.indexWhere(_.is[RightParen]) + 1,
    )
    Patch.removeTokens(parens)
  }

  final class Power(val g: ScalafixGlobal)(implicit val doc: SemanticDocument)
      extends IPower with impl.IPower

  trait IPower { this: impl.IPower =>
    /** Similar to `nextOverriddenSymbol` but loop through ancestors.reverse
      * @see [[scala.reflect.internal.Symbols.Symbol.nextOverriddenSymbol]] */
    private def rootOverriddenSymbol(s: g.Symbol): g.Symbol = {
      import g._, s._
      @tailrec def loop(bases: List[Symbol]): Symbol = bases match {
        case Nil          => NoSymbol
        case base :: rest =>
          val sym = overriddenSymbol(base)
          if (sym == NoSymbol) loop(rest) else sym
      }
      if (isOverridingSymbol) loop(owner.ancestors.reverse) else NoSymbol
    }
    def isNullaryMethod(t: Tree): Option[Boolean] = try {
      val meth = gsymbol(t)
      val isJavaDefined = meth.overrideChain.exists(sym => sym.isJavaDefined || sym.owner == g.definitions.AnyClass)

      if (isJavaDefined) None
      else rootOverriddenSymbol(meth) match {
        case m: g.MethodSymbol => Some(cond(m.info) {
          case g.NullaryMethodType(_) | g.PolyType(_, _: g.NullaryMethodType)=> true
        })
        case _ => None
      }
    } catch {
      case e: Throwable => throw CompilerException(e)
    }
  }
}

import scala.io.Source
import scala.collection.mutable
import metaconfig._
import metaconfig.generic.Surface
import scalafix.internal.config.ReaderUtil
import NullaryOverrideConfig._

case class NullaryOverrideConfig(
    mode: NullaryOverrideMode = CollectAppend,
    nullarySymPath: Path = Paths.get(".nullary.NullaryOverride"),
    nonNullarySymPath: Path = Paths.get(".nonNullary.NullaryOverride")
) {
  val nullarySymbols: mutable.Set[String] = mutable.Set.empty
  val nonNullarySymbols: mutable.Set[String] = mutable.Set.empty
  lazy val nullaryMatcher: SymbolMatcher = matcher(nullarySymFile)
  lazy val nonNullaryMatcher: SymbolMatcher = matcher(nonNullarySymFile)

  val nullarySymFile: File = nullarySymPath.toFile
  val nonNullarySymFile: File = nonNullarySymPath.toFile

  def saveCollected(): Unit = mode match {
    case ResetAndCollect =>
      Using(new FileWriter(nullarySymFile))(writeSymbols(nullarySymbols, _))
      Using(new FileWriter(nonNullarySymFile))(writeSymbols(nonNullarySymbols, _))
    case CollectAppend =>
      Using.resources(
        Source.fromFile(nullarySymFile),
        new FileWriter(nullarySymFile)
      ) { (src, fw) =>
        writeSymbols(src.getLines().toSet ++ nullarySymbols, fw)
      }
      Using.resources(
        Source.fromFile(nonNullarySymFile),
        new FileWriter(nonNullarySymFile)
      ){ (src, fw) =>
        writeSymbols(src.getLines().toSet ++ nonNullarySymbols, fw)
      }
    case Rewrite => // do nothing
  }
}

object NullaryOverrideConfig {
  val default: NullaryOverrideConfig = NullaryOverrideConfig()
  implicit val reader: ConfDecoder[NullaryOverrideConfig] = generic.deriveDecoder[NullaryOverrideConfig](default)
  implicit val surface: Surface[NullaryOverrideConfig] = generic.deriveSurface[NullaryOverrideConfig]

  private def matcher(f: File) = SymbolMatcher.exact(
    Using(Source.fromFile(f))(_.getLines.toList).get: _*
  )

  private def writeSymbols(symbols: Iterable[String], fw: FileWriter): Unit =
    symbols.foreach { sym =>
      fw.write(sym)
      fw.write('\n')
    }
}

sealed trait NullaryOverrideMode
object NullaryOverrideMode {
  case object ResetAndCollect extends NullaryOverrideMode
  case object CollectAppend extends NullaryOverrideMode
  case object Rewrite extends NullaryOverrideMode

  private def all = Seq(ResetAndCollect, CollectAppend, Rewrite)
  implicit val reader: ConfDecoder[NullaryOverrideMode] =
    ReaderUtil.fromMap(all.map(x => x.toString -> x).toMap)
}