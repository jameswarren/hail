package org.broadinstitute.hail.expr

import org.broadinstitute.hail.Utils._
import scala.collection.mutable.ArrayBuffer
import scala.util.parsing.combinator.JavaTokenParsers
import scala.util.parsing.input.Position

object ParserUtils {
  def error(pos: Position, msg: String): Nothing = {
    val lineContents = pos.longString.split("\n").head
    val prefix = s"<input>:${pos.line}:"
    fatal(
      s"""$msg
         |$prefix$lineContents
         |${" " * prefix.length}${
        lineContents.take(pos.column - 1).map { c => if (c == '\t') c else ' ' }
      }^""".stripMargin)
  }
}

object Parser extends JavaTokenParsers {
  def parse[T](symTab: Map[String, (Int, Type)], expected: Type, a: ArrayBuffer[Any], code: String): () => T = {
    // println(s"code = $code")
    val t: AST = parseAll(expr, code) match {
      case Success(result, _) => result
      case NoSuccess(msg, next) => ParserUtils.error(next.pos, msg)
    }

    t.typecheck(symTab)
    if (expected != null
      && t.`type` != expected)
      fatal(s"expression has wrong type: expected `$expected', got ${t.`type`}")

    val f: () => Any = t.eval(EvalContext(symTab, a))
    () => f().asInstanceOf[T]
  }

  def parseType(code: String): Type = {
    // println(s"code = $code")
    parseAll(type_expr, code) match {
      case Success(result, _) => result
      case NoSuccess(msg, next) => ParserUtils.error(next.pos, msg)
    }
  }

  def parseAnnotationTypes(code: String): Map[String, Type] = {
    // println(s"code = $code")
    if (code.isEmpty)
      Map.empty[String, Type]
    else
      parseAll(struct_fields, code) match {
        case Success(result, _) => result.toMap
        case NoSuccess(msg, next) => ParserUtils.error(next.pos, msg)
      }
  }

  def withPos[T](p: => Parser[T]): Parser[Positioned[T]] =
    positioned[Positioned[T]](p ^^ { x => Positioned(x) })

  def parseExportArgs(symTab: Map[String, (Int, Type)],
    a: ArrayBuffer[Any],
    code: String): (Option[String], Array[() => Any]) = {
    val (header, ts) = parseAll(export_args, code) match {
      case Success(result, _) => result.asInstanceOf[(Option[String], Array[AST])]
      case NoSuccess(msg, _) => fatal(msg)
    }

    ts.foreach(_.typecheck(symTab))
    val fs = ts.map { t =>
      t.eval(EvalContext(symTab, a))
    }
    (header, fs)
  }

  def parseAnnotationArgs(symTab: Map[String, (Int, Type)],
    a: ArrayBuffer[Any],
    code: String): (Array[(List[String], Type, () => Any)]) = {
    val arr = parseAll(annotationExpressions, code) match {
      case Success(result, _) => result.asInstanceOf[Array[(Array[String], AST)]]
      case NoSuccess(msg, _) => fatal(msg)
    }

    arr.map {
      case (path, ast) =>
        ast.typecheck(symTab)
        (path.toList, ast.`type`, ast.eval(EvalContext(symTab, a)))
    }
  }

  def expr: Parser[AST] = ident ~ withPos("=>") ~ expr ^^ { case param ~ arrow ~ body =>
    Lambda(arrow.pos, param, body)
  } |
    if_expr |
    let_expr |
    or_expr

  def if_expr: Parser[AST] =
    withPos("if") ~ ("(" ~> expr <~ ")") ~ expr ~ ("else" ~> expr) ^^ { case ifx ~ cond ~ thenTree ~ elseTree =>
      If(ifx.pos, cond, thenTree, elseTree)
    }

  def let_expr: Parser[AST] =
    withPos("let") ~ rep1sep((identifier <~ "=") ~ expr, "and") ~ ("in" ~> expr) ^^ { case let ~ bindings ~ body =>
      Let(let.pos, bindings.iterator.map { case id ~ v => (id, v) }.toArray, body)
    }

  def or_expr: Parser[AST] =
    and_expr ~ rep(withPos("||" | "|") ~ and_expr) ^^ { case lhs ~ lst =>
      lst.foldLeft(lhs) { case (acc, op ~ rhs) => BinaryOp(op.pos, acc, op.x, rhs) }
    }

  def and_expr: Parser[AST] =
    lt_expr ~ rep(withPos("&&" | "&") ~ lt_expr) ^^ { case lhs ~ lst =>
      lst.foldLeft(lhs) { case (acc, op ~ rhs) => BinaryOp(op.pos, acc, op.x, rhs) }
    }

  def lt_expr: Parser[AST] =
    eq_expr ~ rep(withPos("<=" | ">=" | "<" | ">") ~ eq_expr) ^^ { case lhs ~ lst =>
      lst.foldLeft(lhs) { case (acc, op ~ rhs) => Comparison(op.pos, acc, op.x, rhs) }
    }

  def eq_expr: Parser[AST] =
    add_expr ~ rep(withPos("==" | "!=") ~ add_expr) ^^ { case lhs ~ lst =>
      lst.foldLeft(lhs) { case (acc, op ~ rhs) => Comparison(op.pos, acc, op.x, rhs) }
    }

  def add_expr: Parser[AST] =
    mul_expr ~ rep(withPos("+" | "-") ~ mul_expr) ^^ { case lhs ~ lst =>
      lst.foldLeft(lhs) { case (acc, op ~ rhs) => BinaryOp(op.pos, acc, op.x, rhs) }
    }

  def mul_expr: Parser[AST] =
    tilde_expr ~ rep(withPos("*" | "/" | "%") ~ tilde_expr) ^^ { case lhs ~ lst =>
      lst.foldLeft(lhs) { case (acc, op ~ rhs) => BinaryOp(op.pos, acc, op.x, rhs) }
    }

  def tilde_expr: Parser[AST] =
    dot_expr ~ rep(withPos("~") ~ dot_expr) ^^ { case lhs ~ lst =>
      lst.foldLeft(lhs) { case (acc, op ~ rhs) => BinaryOp(op.pos, acc, op.x, rhs) }
    }

  def export_args: Parser[(Option[String], Array[AST])] =
  // FIXME | not backtracking properly.  Why?
    args ^^ { a => (None, a) } |||
      named_args ^^ { a =>
        (Some(a.map(_._1).mkString("\t")), a.map(_._2))
      }

  def named_args: Parser[Array[(String, AST)]] =
    named_arg ~ rep("," ~ named_arg) ^^ { case arg ~ lst =>
      (arg :: lst.map { case _ ~ arg => arg }).toArray
    }

  def named_arg: Parser[(String, AST)] =
    tsvIdentifier ~ "=" ~ expr ^^ { case id ~ _ ~ expr => (id, expr) }

  def annotationExpressions: Parser[Array[(Array[String], AST)]] =
    rep1sep(annotationExpression, ",") ^^ {
      _.toArray
    }

  def annotationExpression: Parser[(Array[String], AST)] = annotationIdentifier ~ "=" ~ expr ^^ {
    case id ~ eq ~ expr => (id, expr)
  }

  def annotationIdentifier: Parser[Array[String]] =
    rep1sep(identifier, ".") ^^ {
      _.toArray
    }

  def tsvIdentifier: Parser[String] = tickIdentifier | """[^\s\p{Cntrl}=,]+""".r

  def tickIdentifier: Parser[String] = """`[^`]+`""".r ^^ { i => i.substring(1, i.length - 1) }

  def identifier = tickIdentifier | ident

  def args: Parser[Array[AST]] =
    repsep(expr, ",") ^^ {
      _.toArray
    }

  def dot_expr: Parser[AST] =
    unary_expr ~ rep((withPos(".") ~ identifier ~ "(" ~ args ~ ")")
      | (withPos(".") ~ identifier)
      | withPos("[") ~ expr ~ "]") ^^ { case lhs ~ lst =>
      lst.foldLeft(lhs) { (acc, t) => (t: @unchecked) match {
        case (dot: Positioned[_]) ~ sym => Select(dot.pos, acc, sym)
        case (dot: Positioned[_]) ~ (sym: String) ~ "(" ~ (args: Array[AST]) ~ ")" => ApplyMethod(dot.pos, acc, sym, args)
        case (lbracket: Positioned[_]) ~ (idx: AST) ~ "]" => IndexArray(lbracket.pos, acc, idx)
      }
      }
    }

  def unary_expr: Parser[AST] =
    rep(withPos("-" | "!")) ~ primary_expr ^^ { case lst ~ rhs =>
      lst.foldRight(rhs) { case (op, acc) =>
        UnaryOp(op.pos, op.x, acc)
      }
    }

  // """"([^"\p{Cntrl}\\]|\\[\\'"bfnrt])*"""".r
  def evalStringLiteral(lit: String): String = {
    assert(lit.head == '"' && lit.last == '"')
    val r = """\\[\\'"bfnrt]""".r
    // replacement does backslash expansion
    r.replaceAllIn(lit.tail.init, _.matched)
  }

  def primary_expr: Parser[AST] =
    withPos("""-?\d*\.\d+[dD]?""".r) ^^ (r => Const(r.pos, r.x.toDouble, TDouble)) |
      withPos("""-?\d+(\.\d*)?[eE][+-]?\d+[dD]?""".r) ^^ (r => Const(r.pos, r.x.toDouble, TDouble)) |
      // FIXME L suffix
      withPos(wholeNumber) ^^ (r => Const(r.pos, r.x.toInt, TInt)) |
      withPos(""""([^"\p{Cntrl}\\]|\\[\\'"bfnrt])*"""".r) ^^ { r =>
        Const(r.pos, evalStringLiteral(r.x), TString)
      } |
      withPos("true") ^^ (r => Const(r.pos, true, TBoolean)) |
      withPos("false") ^^ (r => Const(r.pos, false, TBoolean)) |
      guard(not("if" | "else")) ~> withPos(identifier) ^^ (r => SymRef(r.pos, r.x)) |
      "{" ~> expr <~ "}" |
      "(" ~> expr <~ ")"

  def annotationSignature: Parser[TStruct] =
    struct_fields ^^ { fields => TStruct(fields: _*) }

  def struct_field: Parser[(String, Type)] =
    (identifier <~ ":") ~ type_expr ^^ { case name ~ t =>
      (name, t)
    }

  def struct_fields: Parser[Array[(String, Type)]] = rep1sep(struct_field, ",") ^^ {
    _.toArray
  }

  def type_expr: Parser[Type] =
    "Empty" ^^ { _ => TEmpty } |
      "Boolean" ^^ { _ => TBoolean } |
      "Char" ^^ { _ => TChar } |
      "Int" ^^ { _ => TInt } |
      "Long" ^^ { _ => TLong } |
      "Float" ^^ { _ => TFloat } |
      "Double" ^^ { _ => TDouble } |
      "String" ^^ { _ => TString } |
      "Sample" ^^ { _ => TSample } |
      "AltAllele" ^^ { _ => TAltAllele } |
      "Variant" ^^ { _ => TVariant } |
      "Genotype" ^^ { _ => TGenotype } |
      "String" ^^ { _ => TString } |
      ("Array" ~ "[") ~> type_expr <~ "]" ^^ { elementType => TArray(elementType) } |
      ("Struct" ~ "(") ~> struct_fields <~ ")" ^^ { fields =>
        TStruct(fields: _*)
      }
}