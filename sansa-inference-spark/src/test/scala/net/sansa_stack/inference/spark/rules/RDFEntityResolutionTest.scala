package net.sansa_stack.inference.spark.rules

import net.sansa_stack.inference.spark.data.model.RDFGraph
import net.sansa_stack.inference.spark.data.writer.RDFGraphWriter
import net.sansa_stack.inference.spark.forwardchaining.triples.ForwardRuleReasonerRDFS
import org.apache.jena.graph.Triple
import org.apache.jena.rdf.model.ModelFactory
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable

/**
  * The class to compute the materialization of a given RDF graph.
  *
  * @author Lorenz Buehmann
  *
  */
object RDFEntityResolutionTest {

  def main(args: Array[String]) {
    // the SPARK config
    val conf = new SparkConf().setAppName("SPARK Reasoning")
    conf.set("spark.hadoop.validateOutputSpecs", "false")
    conf.setMaster("local[2]")
    conf.set("spark.eventLog.enabled", "true")
    val sc = new SparkContext(conf)

    val m = ModelFactory.createDefaultModel()
    m.read(RDFEntityResolutionTest.getClass.getClassLoader.getResourceAsStream("data/owl-horst-minimal2.ttl"), null, "TURTLE")

    val triples = new mutable.HashSet[Triple]()
    val iter = m.listStatements()
    while (iter.hasNext) {
      val st = iter.next()
      triples.add(st.asTriple())
    }
    // Step 1 (Manual):
    // manual expressing of semantic connections between ontologies.
    // The most common are: rdfs:subClassOf, owl:equivalentClass and owl:disjointWith. and also for properties.

    // Step 2 (TBOX: Only axioms): Inference mechanism of semantic connections. (Automatic Inference layer)
    // Based on the minimum semantic connections expressed manually in step 1,
    // the automatic inference algorithms are used to infer all possible semantic connections.
    // The saturation inference strategy consists of applying all propagation/inference-
    // rules to the TBox until no more new axioms are derived (fix point).
    // FIXEDPOINT: Finding all the axioms.

    // (1) ds1 : House ? ds2 : Housing, (2) ds1 : House ? ∃ds1 : hasOwner.dbpedia : Person,
    // (3) ds1 : Capital ? dbpedia : City, (4) ds1 : located ? ds2 : hasAddress,
    // (5) dbpedia : City ? ¬foaf : Person.

    // The subsumption reasoning on the conceptual part of knowledge base makes the following inferences automatic:
    // (i) from (3) and DBpedia knowledge base ⇒ ds1 : Capital ? dbpedia : Settlement, which means that the DS1 concept Capital is a Settlement of DBpedia.
    // (ii) from (3) and (5) ⇒ ds1 : Capital ? ¬foaf : Person, which means that the DS1 concept Capital is disjoint from FOAF concept Person.

    // The inference knowledge illustrates two types of axioms
    // that are important for entity resolution: sub-concept (? ) and disjoint (? ¬) relationships.


    // Step 3 (ABOX: Instances): MapReduce-based Inference for entity resolution
    // IMPORTANT: (Semantic entity resolution). Semantic entity resolution is based on a functional key that includes the property rdf:type

    // (?xi rdf:type ds1:Location ) ∧ (?yj rdf:type ds1:Location ) ∧ (?xi ds2:street ?si) ∧ (?yj ds2:street ?sj)∧(?si owl:sameAs ?sj) ⇒ (?xi owl:sameAs ?yj)

    // Based on the unique data linking {ds1:paris owl:sameAs ds2:dep75}, rule (R2) is triggered by variable unification
    // {?xi = ds1 : ad03, ?yi = ds1 : ad25, ?si =?sj = 1 eiffel st.}, which
    // generates the entity resolution {ds1 : ad03 owl : sameAs ds2 : ad25}, that is, ds1 : ad03 and ds2 : ad25 are RDF fragments of the same real work address.

    val triplesRDD = sc.parallelize(triples.toSeq, 2)

    val graph = RDFGraph(triplesRDD)

    // create reasoner
    val reasoner = new ForwardRuleReasonerRDFS(sc)

    // compute inferred graph
    val inferredGraph = reasoner.apply(graph)

    // write triples to disk
    RDFGraphWriter.writeToDisk(inferredGraph, args(0))

    sc.stop()


  }
}
