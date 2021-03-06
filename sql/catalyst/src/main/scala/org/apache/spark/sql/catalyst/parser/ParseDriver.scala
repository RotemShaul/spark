/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.parser

import org.antlr.runtime._
import org.antlr.runtime.tree.CommonTree

import org.apache.spark.Logging
import org.apache.spark.sql.AnalysisException

/**
 * The ParseDriver takes a SQL command and turns this into an AST.
 *
 * This is based on Hive's org.apache.hadoop.hive.ql.parse.ParseDriver
 */
object ParseDriver extends Logging {
  def parse(command: String, conf: ParserConf): ASTNode = {
    logInfo(s"Parsing command: $command")

    // Setup error collection.
    val reporter = new ParseErrorReporter()

    // Create lexer.
    val lexer = new SparkSqlLexer(new ANTLRNoCaseStringStream(command))
    val tokens = new TokenRewriteStream(lexer)
    lexer.configure(conf, reporter)

    // Create the parser.
    val parser = new SparkSqlParser(tokens)
    parser.configure(conf, reporter)

    try {
      val result = parser.statement()

      // Check errors.
      reporter.checkForErrors()

      // Return the AST node from the result.
      logInfo(s"Parse completed.")

      // Find the non null token tree in the result.
      def nonNullToken(tree: CommonTree): CommonTree = {
        if (tree.token != null || tree.getChildCount == 0) tree
        else nonNullToken(tree.getChild(0).asInstanceOf[CommonTree])
      }
      val tree = nonNullToken(result.getTree)

      // Make sure all boundaries are set.
      tree.setUnknownTokenBoundaries()

      // Construct the immutable AST.
      def createASTNode(tree: CommonTree): ASTNode = {
        val children = (0 until tree.getChildCount).map { i =>
          createASTNode(tree.getChild(i).asInstanceOf[CommonTree])
        }.toList
        ASTNode(tree.token, tree.getTokenStartIndex, tree.getTokenStopIndex, children, tokens)
      }
      createASTNode(tree)
    }
    catch {
      case e: RecognitionException =>
        logInfo(s"Parse failed.")
        reporter.throwError(e)
    }
  }
}

/**
 * This string stream provides the lexer with upper case characters only. This greatly simplifies
 * lexing the stream, while we can maintain the original command.
 *
 * This is based on Hive's org.apache.hadoop.hive.ql.parse.ParseDriver.ANTLRNoCaseStringStream
 *
 * The comment below (taken from the original class) describes the rationale for doing this:
 *
 * This class provides and implementation for a case insensitive token checker for the lexical
 * analysis part of antlr. By converting the token stream into upper case at the time when lexical
 * rules are checked, this class ensures that the lexical rules need to just match the token with
 * upper case letters as opposed to combination of upper case and lower case characters. This is
 * purely used for matching lexical rules. The actual token text is stored in the same way as the
 * user input without actually converting it into an upper case. The token values are generated by
 * the consume() function of the super class ANTLRStringStream. The LA() function is the lookahead
 * function and is purely used for matching lexical rules. This also means that the grammar will
 * only accept capitalized tokens in case it is run from other tools like antlrworks which do not
 * have the ANTLRNoCaseStringStream implementation.
 */

private[parser] class ANTLRNoCaseStringStream(input: String) extends ANTLRStringStream(input) {
  override def LA(i: Int): Int = {
    val la = super.LA(i)
    if (la == 0 || la == CharStream.EOF) la
    else Character.toUpperCase(la)
  }
}

/**
 * Utility used by the Parser and the Lexer for error collection and reporting.
 */
private[parser] class ParseErrorReporter {
  val errors = scala.collection.mutable.Buffer.empty[ParseError]

  def report(br: BaseRecognizer, re: RecognitionException, tokenNames: Array[String]): Unit = {
    errors += ParseError(br, re, tokenNames)
  }

  def checkForErrors(): Unit = {
    if (errors.nonEmpty) {
      val first = errors.head
      val e = first.re
      throwError(e.line, e.charPositionInLine, first.buildMessage().toString, errors.tail)
    }
  }

  def throwError(e: RecognitionException): Nothing = {
    throwError(e.line, e.charPositionInLine, e.toString, errors)
  }

  private def throwError(
      line: Int,
      startPosition: Int,
      msg: String,
      errors: Seq[ParseError]): Nothing = {
    val b = new StringBuilder
    b.append(msg).append("\n")
    errors.foreach(error => error.buildMessage(b).append("\n"))
    throw new AnalysisException(b.toString, Option(line), Option(startPosition))
  }
}

/**
 * Error collected during the parsing process.
 *
 * This is based on Hive's org.apache.hadoop.hive.ql.parse.ParseError
 */
private[parser] case class ParseError(
    br: BaseRecognizer,
    re: RecognitionException,
    tokenNames: Array[String]) {
  def buildMessage(s: StringBuilder = new StringBuilder): StringBuilder = {
    s.append(br.getErrorHeader(re)).append(" ").append(br.getErrorMessage(re, tokenNames))
  }
}
