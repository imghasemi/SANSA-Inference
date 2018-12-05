package net.sansa_stack.inference.spark.entityresolution

import net.sansa_stack.inference.spark.data.model.RDFGraph
import net.sansa_stack.inference.spark.data.writer.RDFGraphWriter
import net.sansa_stack.inference.spark.forwardchaining.triples.{ForwardRuleReasonerEL, ForwardRuleReasonerOWLHorst, ForwardRuleReasonerRDFS}
import org.apache.jena.graph.Triple
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.{OWL2, RDF}
import org.apache.spark.{SparkConf, SparkContext}
import net.sansa_stack.inference.spark.data.model.TripleUtils._


import scala.collection.mutable

class EREntitySerializer {

}

// EXAMPLE: (?xi rdf:type ds1:Location ) ∧ (?yj rdf:type ds1:Location ) ∧ (?xi ds2:street ?si) ∧ (?yj ds2:street ?sj)∧(?si owl:sameAs ?sj) ⇒ (?xi owl:sameAs ?yj)


// (Semantic entity resolution).
// Semantic entity resolution is based on a functional key that includes the property rdf : type.
// That is, given functional key fk = {p1, ..., pn}
// and two entity fragments EFi and EFj
case class EREntitySerializerSemanticResolutionSet(typeOfEntityURI: String, entityFragment1: String, entityFragment2: String = null)

object EREntitySerializerTest {

  def main(args: Array[String]) {
    // the SPARK config
    val conf = new SparkConf().setAppName("SPARK Reasoning")
    conf.set("spark.hadoop.validateOutputSpecs", "false")
    conf.setMaster("local[2]")
    conf.set("spark.eventLog.enabled", "true")
    val sc = new SparkContext(conf)

    val m = ModelFactory.createDefaultModel()
    m.read(this.getClass.getClassLoader.getResourceAsStream("ER/sample2.ttl"), null, "TURTLE")

    val triples = new mutable.HashSet[Triple]()
    val iter = m.listStatements()
    while (iter.hasNext) {
      val st = iter.next()
      triples.add(st.asTriple())
    }

    // functional keys are provided by datasource experts
    val functionalKeys = EREntitySerializerSemanticResolutionSet("http://datasource2.org/Location", "http://datasource2.org/inCity", "http://datasource2.org/street")
    // val functionalKeys = EREntitySerializerFunctionalProperties("http://datasource2.org/Location", "http://datasource2.org/inCity")

    val triplesRDD = sc.parallelize(triples.toSeq, 2)

    val graph = RDFGraph(triplesRDD)

    // create reasoner
    val reasoner = new ForwardRuleReasonerOWLHorst(sc)

    // compute inferred graph
    val inferredGraph = reasoner.apply(graph)

    val inferredGraphCollected = inferredGraph.triples.collect()
    println("======================================")
    println("|        INFERRED TRIPLES            |")
    println("======================================")
    inferredGraphCollected.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")
    // write triples to disk
    // RDFGraphWriter.writeToDisk(inferredGraph, args(0))
    // RDFGraphWriter.writeToDisk(inferredGraph, "output/data/EREntitySerializer")
    val cachedRDDGraph = inferredGraph.triples.cache()
    val serializeRDDGraph = cachedRDDGraph
    /*
          .map(t => (t.s, (t.p, t.o)))
    */
    val sameAsTriples = cachedRDDGraph
      .filter(t => (t.p.getURI == RDF.`type`.getURI && t.o.toString() == functionalKeys.typeOfEntityURI)
        || t.getPredicate.getURI == functionalKeys.entityFragment1 || t.getPredicate.getURI == functionalKeys.entityFragment2)
      /* Maps to the tuple of subjects and objects */
      .map(t => (t.s, t.o))
      /* Group based on the Triple subject */
      .groupBy(_._1)
      /* serialized data based on the same keys */
      .map(t => t._1 -> t._2.map(_._2))

    sameAsTriples.collect().foreach(println)


    sc.stop()
  }

  def filterPass(): Unit = {

  }


}