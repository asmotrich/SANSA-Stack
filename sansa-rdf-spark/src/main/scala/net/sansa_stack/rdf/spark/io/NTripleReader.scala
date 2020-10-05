package net.sansa_stack.rdf.spark.io

import java.net.URI
import java.util.UUID

import scala.reflect.ClassTag
import com.google.common.base.Predicates
import com.google.common.collect.Iterators
import net.sansa_stack.rdf.benchmark.io.ReadableByteChannelFromIterator
import net.sansa_stack.rdf.common.io.riot.lang.LangNTriplesSkipBad
import net.sansa_stack.rdf.common.io.riot.tokens.TokenizerTextForgiving
import org.apache.jena.atlas.io.PeekReader
import org.apache.jena.atlas.iterator.IteratorResourceClosing
import org.apache.jena.graph.Triple
import org.apache.jena.riot.{RIOT, SysRIOT}
import org.apache.jena.riot.SysRIOT.fmtMessage
import org.apache.jena.riot.lang.{LabelToNode, RiotParsers}
import org.apache.jena.riot.system._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}
import net.sansa_stack.rdf.common.io.riot.error.{CustomErrorHandler, ErrorParseMode, WarningParseMode}

/**
 * An N-Triples reader. One triple per line is assumed.
 *
 * @author Lorenz Buehmann
 */
object NTripleReader {

  /**
   * Loads N-Triples data from a file or directory into an RDD.
   *
   * @param session the Spark session
   * @param path    the path to the N-Triples file(s)
   * @return the RDD of triples
   */
  def load(session: SparkSession, path: URI): RDD[Triple] = {
    load(session, path.toString)
  }

  /**
   * Loads N-Triples data from a set of files or directories into an RDD.
   * The path can also contain multiple paths
   * and even wildcards, e.g.
   * `"/my/dir1,/my/paths/part-00[0-5]*,/another/dir,/a/specific/file"`
   *
   * @param session the Spark session
   * @param paths   the path to the N-Triples file(s)
   * @return the RDD of triples
   */
  def load(session: SparkSession, paths: Seq[URI]): RDD[Triple] = {
    load(session, paths.mkString(","))
  }

  /**
   * Loads N-Triples data from a file or directory into an RDD.
   * The path can also contain multiple paths
   * and even wildcards, e.g.
   * `"/my/dir1,/my/paths/part-00[0-5]*,/another/dir,/a/specific/file"`
   *
   * === Handling of errors===
   *
   * By default, it stops once a parse error occurs, i.e. a [[org.apache.jena.riot.RiotException]] will be thrown
   * generated by the underlying parser.
   *
   * The following options exist:
   *  - STOP the whole data loading process will be stopped and a `org.apache.jena.net.sansa_stack.rdf.spark.riot.RiotException` will be thrown
   *  - SKIP the line will be skipped but the data loading process will continue, an error message will be logged
   *
   *
   * ===Handling of warnings===
   *
   * If the additional checking of RDF terms is enabled, warnings during parsing can occur. For example,
   * a wrong lexical form of a literal w.r.t. to its datatype will lead to a warning.
   *
   * The following can be done with those warnings:
   *  - IGNORE the warning will just be logged to the configured logger
   *  - STOP similar to the error handling mode, the whole data loading process will be stopped and a
   *  [[org.apache.jena.riot.RiotException]] will be thrown
   *  - SKIP similar to the error handling mode, the line will be skipped but the data loading process will continue
   *
   *
   * ===Checking of RDF terms===
   * Set whether to perform checking of NTriples - defaults to no checking.
   *
   * Checking adds warnings over and above basic syntax errors.
   * This can also be used to turn warnings into exceptions if the option `stopOnWarnings` is set to STOP or SKIP.
   *
   *  - IRIs - whether IRIs confirm to all the rules of the IRI scheme
   *  - Literals: whether the lexical form conforms to the rules for the datatype.
   *  - Triples: check slots have a valid kind of RDF term (parsers usually make this a syntax error anyway).
   *
   *
   * See also the optional `errorLog` argument to control the output. The default is to log.
   *
   *
   * @param session        the Spark session
   * @param path           the path to the N-Triples file(s)
   * @param stopOnBadTerm  stop parsing on encountering a bad RDF term
   * @param stopOnWarnings stop parsing on encountering a warning
   * @param checkRDFTerms  run with checking of literals and IRIs either on or off
   * @param errorLog       the logger used for error message handling
   * @return the RDD of triples
   */
  def load(session: SparkSession, path: String,
           stopOnBadTerm: ErrorParseMode.Value = ErrorParseMode.STOP,
           stopOnWarnings: WarningParseMode.Value = WarningParseMode.IGNORE,
           checkRDFTerms: Boolean = false,
           errorLog: Logger = ErrorHandlerFactory.stdLogger): RDD[Triple] = {

    // parse the text file first
    val rdd = session.sparkContext
      .textFile(path, minPartitions = 20)

    val strict = stopOnBadTerm == ErrorParseMode.STOP && stopOnWarnings == WarningParseMode.STOP

    // create the error handler profile
    val profileWrapper = NonSerializableObjectWrapper {
      val errorHandler =
        if (strict) {
          ErrorHandlerFactory.errorHandlerStrict(errorLog)
        } else {
          if (stopOnBadTerm == ErrorParseMode.STOP) {
            if (stopOnWarnings == WarningParseMode.STOP || stopOnWarnings == WarningParseMode.SKIP) {
              ErrorHandlerFactory.errorHandlerStrict(errorLog)
            } else {
              ErrorHandlerFactory.errorHandlerStd(errorLog)
            }
          } else {
            //            ErrorHandlerFactory.errorHandlerWarn
            new CustomErrorHandler()
          }
        }

      val seed = new UUID(path.hashCode, 0)
      new ParserProfileStd(RiotLib.factoryRDF(LabelToNode.createScopeByDocumentHash(seed)),
        errorHandler,
        IRIResolver.create,
        PrefixMapFactory.createForInput,
        RIOT.getContext.copy,
        checkRDFTerms || strict, strict)
    }
    import scala.collection.JavaConverters._

    // parse each partition
    rdd.mapPartitions(p => {
      // convert iterator to input stream
      val input = ReadableByteChannelFromIterator.toInputStream(p.asJava)

      // create the parsing iterator
      val it =
        if (stopOnBadTerm == ErrorParseMode.STOP || stopOnWarnings == WarningParseMode.STOP) {
          // this is the default behaviour of Jena, i.e. once a parse error occurs the whole process stops
          RiotParsers.createIteratorNTriples(input, null, profileWrapper.get)
        } else {
          // here we "simply" skip illegal triples

          // we need a custom tokenizer
          val tokenizer = new TokenizerTextForgiving(PeekReader.makeUTF8(input))
          tokenizer.setErrorHandler(ErrorHandlerFactory.errorHandlerWarn)

          // which is used by a custom N-Triples iterator
          val it = new LangNTriplesSkipBad(tokenizer, profileWrapper.get, null)

          // filter out null values
          Iterators.filter(it, Predicates.notNull[Triple]())
        }
      new IteratorResourceClosing[Triple](it, input).asScala
    })
  }

  private case class Config(
                     in: URI = null,
                     mode: String = "",
                     sampleSize: Int = 10)

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[Config]("N-Triples Reader") {

      head("N-Triples Reader", "0.7.2")

      cmd("triples")
        .text("compute number of triples")
        .action((x, c) => c.copy(mode = "triples"))

      cmd("sample")
        .text("show sample of triples")
        .action((x, c) => c.copy(mode = "sample"))
        .children(
          opt[Int]("size")
            .abbr("n")
            .action((x, c) => c.copy(sampleSize = x))
            .text("sample size (too high number can be slow or lead to memory issues)"),
          checkConfig(
            c =>
              if (c.mode == "sample" && c.sampleSize <= 0) failure("sample size must be > 0")
              else success)
        )

      arg[URI]("<file>")
        .action((x, c) => c.copy(in = x))
        .text("URI to N-Triples file to process")
        .valueName("<file>")
        .required()

    }
    // parser.parse returns Option[C]
    parser.parse(args, Config()) match {
      case Some(config) =>
        val sparkSession = SparkSession.builder
          //                .master("local")
          .appName("N-Quads reader")
          .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
          .getOrCreate()

        val rdd = NTripleReader.load(
          sparkSession,
          config.in.getPath,
          stopOnBadTerm = ErrorParseMode.SKIP,
          stopOnWarnings = WarningParseMode.SKIP,
          checkRDFTerms = true,
          LoggerFactory.getLogger("errorLog"))

        config.mode match {
          case "triples" => println(s"#parsed triples: ${rdd.count()}")
          case "sample" => println(s"max ${config.sampleSize} sample triples:\n"
            + rdd.take(config.sampleSize).map { _.toString.replaceAll("[\\x00-\\x1f]", "???") }.mkString("\n"))
        }

        sparkSession.stop()

      case None =>
      // arguments are bad, error message will have been displayed
    }
  }

}

private class NonSerializableObjectWrapper[T: ClassTag](constructor: => T)
  extends AnyRef with Serializable {
  @transient private lazy val instance: T = constructor

  def get: T = instance
}

private object NonSerializableObjectWrapper {
  def apply[T: ClassTag](constructor: => T): NonSerializableObjectWrapper[T] = new NonSerializableObjectWrapper[T](constructor)
}
