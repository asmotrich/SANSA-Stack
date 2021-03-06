package net.sansa_stack.owl.spark

import net.sansa_stack.owl.spark.rdd._
import net.sansa_stack.owl.spark.writers._
import org.apache.hadoop.fs.Path
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.semanticweb.owlapi.formats._
import org.semanticweb.owlapi.model.{AxiomType, OWLAxiom, OWLDocumentFormat}

class UnknownOWLFormatException(msg: String) extends Exception

/**
 * Wrap up implicit classes/methods to read OWL data into either
 * [[org.apache.spark.sql.Dataset]] or [[org.apache.spark.rdd.RDD]].
 *
 * @author Gezim Sejdiu
 * @author Patrick Westphal
 */
package object owl {

  /**
    * SaveMode is used to specify the expected behavior of saving an OWL dataset/RDD to a path.
    *
    * FIXME: This code was copied from SANSA-RDF and should go into a dedicated SANSA commons package
    */
  object SaveMode extends Enumeration {
    type SaveMode = Value
    val

    /**
      * Overwrite mode means that when saving an OWL dataset/RDD to a path,
      * if path already exists, the existing data is expected to be
      * overwritten by the contents of the RDF dataset.
      */
    Overwrite,

    /**
      * ErrorIfExists mode means that when saving an OWL dataset/RDD to a
      * path, if path already exists, an exception is expected to be thrown.
      */
    ErrorIfExists,

    /**
      * Ignore mode means that when saving an OWL dataset/RDD to a path, if
      * path already exists, the save operation is expected to not save the
      * contents of the RDF dataset and to not change the existing data.
      */
    Ignore = Value
  }

  object Syntax extends Enumeration {
    val FUNCTIONAL, MANCHESTER, OWLXML = Value
  }

  /**
   * Adds methods, `owl(syntax: Syntax)`, `functional` and `manchester`, to
   * [[org.apache.spark.sql.SparkSession]] that allows to read owl files.
   */
  implicit class OWLAxiomReader(spark: SparkSession) {

    /**
     * Load RDF data into a [[org.apache.spark.rdd.RDD]][OWLAxiom]. Currently,
     * only functional, manchester and OWL/XML syntax are supported
     * @param syntax of the OWL (functional, manchester or OWL/XML)
     * @return a [[net.sansa_stack.owl.spark.rdd.OWLAxiomsRDD]]
     */
    def owl(syntax: Syntax.Value): String => OWLAxiomsRDD = syntax match {
      case Syntax.FUNCTIONAL => functional
      case Syntax.MANCHESTER => manchester
      case Syntax.OWLXML => owlXml
      case _ => throw new IllegalArgumentException(
        s"$syntax syntax not supported yet!")
    }

    /**
     * Load OWL data in Functional syntax into an
     * [[org.apache.spark.rdd.RDD]][OWLAxiom].
     * @return the [[net.sansa_stack.owl.spark.rdd.OWLAxiomsRDD]]
     */
    def functional: String => OWLAxiomsRDD = path => {
      FunctionalSyntaxOWLAxiomsRDDBuilder.build(spark, path)
    }

    /**
     * Load OWL data in Manchester syntax into an
     * [[org.apache.spark.rdd.RDD]][OWLAxiom].
     * @return the [[net.sansa_stack.owl.spark.rdd.OWLAxiomsRDD]]
     */
    def manchester: String => OWLAxiomsRDD = path => {
      ManchesterSyntaxOWLAxiomsRDDBuilder.build(spark, path)
    }

    /**
      * Load OWL data in OWL/XML syntax into an
      * [[org.apache.spark.rdd.RDD]][OWLAxiom].
      * @return the [[net.sansa_stack.owl.spark.rdd.OWLAxiomsRDD]]
      */
    def owlXml: String => OWLAxiomsRDD = path => {
      OWLXMLSyntaxOWLAxiomsRDDBuilder.build(spark, path)
    }

  }

  implicit class OWLExpressionsRDDReader(spark: SparkSession) {
    /**
      * Load RDF data into an [[org.apache.spark.rdd.RDD]][String]. Currently,
      * only functional, manchester and OWL/XML syntax are supported
      * @param syntax of the OWL (functional or manchester or OWL/XML)
      * @return a [[net.sansa_stack.owl.spark.rdd.OWLExpressionsRDD]]
      */
     def owlExpressions(syntax: Syntax.Value): String => OWLExpressionsRDD = syntax match {
       case Syntax.FUNCTIONAL => functional
       case Syntax.MANCHESTER => manchester
       case Syntax.OWLXML => owlXml
       case _ => throw new IllegalArgumentException(
         s"$syntax syntax not supported yet!")
     }

     /**
      * Load OWL data in Functional syntax into an
      * [[org.apache.spark.rdd.RDD]][String].
      * @return the [[net.sansa_stack.owl.spark.rdd.OWLExpressionsRDD]]
      */
     def functional: String => OWLExpressionsRDD = path => {
       FunctionalSyntaxOWLExpressionsRDDBuilder.build(spark, path)
     }

     /**
      * Load OWL data in Manchester syntax into an
      * [[org.apache.spark.rdd.RDD]][String].
      * @return the [[net.sansa_stack.owl.spark.rdd.OWLExpressionsRDD]]
      */
     def manchester: String => OWLExpressionsRDD = path => {
       ManchesterSyntaxOWLExpressionsRDDBuilder.build(spark, path)
     }

     /**
       * Load OWL data in OWLXML syntax into an
       * [[org.apache.spark.rdd.RDD]][String].
       * @return the [[net.sansa_stack.owl.spark.rdd.OWLExpressionsRDD]]
       */
     def owlXml: String => OWLExpressionsRDD = path => {
       OWLXMLSyntaxOWLExpressionsRDDBuilder.build(spark, path)._3
     }
  }

  implicit class OWLWriter(axioms: OWLAxiomsRDD) {
    def save(
              path: String,
              format: OWLDocumentFormat,
              saveMode: SaveMode.Value = SaveMode.ErrorIfExists,
              exitOnError: Boolean = false): Unit = {

      val fsPath = new Path(path)
      val fs = fsPath.getFileSystem(axioms.sparkContext.hadoopConfiguration)

      val doSave = if (fs.exists(fsPath)) {
        saveMode match {
          case SaveMode.Overwrite =>
            fs.delete(fsPath, true)
            true
          case SaveMode.ErrorIfExists =>
            sys.error(s"Given path $path already exists!")
            if (exitOnError) sys.exit(1)
            false
          case SaveMode.Ignore => false
          case _ =>
            throw new IllegalStateException(s"Unsupported save mode $saveMode")
        }
      } else {
        true
      }

      if (doSave) {
        format match {
          case _: FunctionalSyntaxDocumentFormat => OWLFunctionalSyntaxWriter.save(path, axioms)
          case format: LabelFunctionalDocumentFormat =>
            throw new NotImplementedError(s"Support for ${format.getClass.getName} not implemented, yet")
          case _: KRSS2DocumentFormat => KRSS2SyntaxWriter.save(path, axioms)
          case _: KRSSDocumentFormat => KRSSSyntaxWriter.save(path, axioms)
          case _: DLSyntaxDocumentFormat => DLSyntaxWriter.save(path, axioms)
          case _: OBODocumentFormat => OBOWriter.save(path, axioms)
          case _: RDFJsonDocumentFormat => RDFJsonWriter.save(path, axioms)
          case _: RDFJsonLDDocumentFormat => JsonLDWriter.save(path, axioms)
          case _: NQuadsDocumentFormat => NQuadsWriter.save(path, axioms)
          case _: NTriplesDocumentFormat => NTriplesWriter.save(path, axioms)
          case _: ManchesterSyntaxDocumentFormat => ManchesterOWLSyntaxWriter.save(path, axioms)
          case _: RDFXMLDocumentFormat => RDFXMLWriter.save(path, axioms)
          case _: TurtleDocumentFormat => TurtleWriter.save(path, axioms)
          case _: N3DocumentFormat => N3Writer.save(path, axioms)
          case _: TrixDocumentFormat => TrixWriter.save(path, axioms)
          case _: OWLXMLDocumentFormat => OWLXMLWriter.save(path, axioms)
          case _ =>
            throw new UnknownOWLFormatException(
              s"OWL serialization format ${format.toString} unknown or not supported")
        }
      }
    }
  }
}

package object owlAxioms {
  def extractAxioms(axioms: RDD[OWLAxiom], T: AxiomType[_]): RDD[OWLAxiom] =
    axioms.filter(a => a.getAxiomType.equals(T))
}
