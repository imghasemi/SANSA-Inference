package net.sansa_stack.inference.spark.entityresolution

import net.sansa_stack.inference.spark.data.model.RDFGraph
import net.sansa_stack.inference.spark.forwardchaining.triples.{ForwardRuleReasonerEL, ForwardRuleReasonerOWLHorst, ForwardRuleReasonerRDFS, TransitiveReasoner}
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.{OWL2, RDF, RDFS}
import org.apache.spark.{SparkConf, SparkContext}
import net.sansa_stack.inference.spark.data.model.TripleUtils._
import org.apache.spark.rdd.RDD
import org.apache.jena.graph.{Node, Triple}
import org.slf4j.LoggerFactory
import scala.collection.mutable


// EXAMPLE: (?xi rdf:type ds1:Location ) ∧ (?yj rdf:type ds1:Location ) ∧ (?xi ds2:street ?si) ∧ (?yj ds2:street ?sj)∧(?si owl:sameAs ?sj) ⇒ (?xi owl:sameAs ?yj)
// (Semantic entity resolution).
// Semantic entity resolution is based on a functional key that includes the property rdf : type.
// That is, given functional key fk = {p1, ..., pn} + all URIs
// and two entity fragments EFi and EFj
case class EREntitySerializerSemanticResolutionSet(typeOfEntityURI: String, entityFragment: String)

class EREntitySerializer(sc: SparkContext, parallelism: Int = 2) extends TransitiveReasoner(sc, parallelism) {
  private val logger = com.typesafe.scalalogging.Logger(LoggerFactory.getLogger(this.getClass.getName))


  def apply(minManualInferencePath: String, dataPath: String, functionalKeys: EREntitySerializerSemanticResolutionSet): RDD[(Node, Iterable[Node])] = {
    logger.info("Serialisation has been started: convert the data into pair(key, list(vals))...")
    val typeOfEntityURI = functionalKeys.typeOfEntityURI
    val entityFragment = functionalKeys.entityFragment
    // val entityFragment2 = functionalKeys.entityFragment2
    val rdfTypeURI = RDF.`type`.getURI

    // @deprecated closure function
    def filterFunctionalKeysTriples(t: Triple): Boolean = {
      t.o.isLiteral || (t.predicateMatches(RDF.`type`.asNode()) && t.o.getURI == typeOfEntityURI) || t.p.getURI == entityFragment
    }

    val startTime = System.currentTimeMillis()

    val m = ModelFactory.createDefaultModel()
    m.read(this.getClass.getClassLoader.getResourceAsStream(dataPath), null, "TURTLE")

    val triples = new mutable.HashSet[Triple]()
    val iter = m.listStatements()
    while (iter.hasNext) {
      val st = iter.next()
      triples.add(st.asTriple())
    }

    val manualInference = ModelFactory.createDefaultModel()
    manualInference.read(this.getClass.getClassLoader.getResourceAsStream(minManualInferencePath), null, "TURTLE")
    val manualInferenceTriples = new mutable.HashSet[Triple]()
    val man = manualInference.listStatements()
    while (man.hasNext) {
      val st = man.next()
      manualInferenceTriples.add(st.asTriple())
    }

    // merge the minimum manual inference with triple data
    triples ++= manualInferenceTriples

    val triplesRDD = sc.parallelize(triples.toSeq, parallelism)

    val graph = RDFGraph(triplesRDD)

    // create reasoner
    val reasoner = new ForwardRuleReasonerOWLHorst(sc)

    // compute inferred graph
    val inferredGraph = reasoner.apply(graph)
    val cachedRDDGraph = inferredGraph.triples.cache()

    println("======================================")
    println("|        INFERRED TRIPLES            |")
    println("======================================")
    cachedRDDGraph.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")


    val functionalEntityFragments = cachedRDDGraph
      .filter(t => t.o.isLiteral || (t.predicateMatches(RDF.`type`.asNode()) && t.o.getURI == typeOfEntityURI) || t.p.getURI == entityFragment)

    val functionalEntities = cachedRDDGraph
      .filter(t => t.o.isLiteral || (t.predicateMatches(RDF.`type`.asNode()) && t.o.getURI == typeOfEntityURI) || t.p.getURI == entityFragment)
      .map(t => t.s.toString())
      .distinct()
    functionalEntityFragments.foreach(println)

    val objectURIsFragment = functionalEntityFragments
      .filter(t => !t.o.isLiteral)
      .map(t => t.o.getURI)
    val sameAsTriplesList = cachedRDDGraph
      .filter(t => t.p == OWL2.sameAs.asNode)
    val equivalentClassTriplesList = cachedRDDGraph
      .filter(t => t.p == OWL2.equivalentClass.asNode)

    val serialzedPackage = sc.union(functionalEntityFragments.distinct(),
      sameAsTriplesList.distinct(),
      equivalentClassTriplesList.distinct())

    // extract the schema data
    var sameAsTriplesExtracted = extractTriples(triplesRDD, OWL2.sameAs.asNode()) // owl:sameAs
    val equivClassTriplesExtracted = extractTriples(triplesRDD, OWL2.equivalentClass.asNode) // owl:equivalentClass
    val equivPropertyTriplesExtracted = extractTriples(triplesRDD, OWL2.equivalentProperty.asNode) // owl:equivalentProperty
    var subClassOfTriplesExtracted = extractTriples(triplesRDD, RDFS.subClassOf.asNode()) // rdfs:subClassOf

    // TODO: the BC properties should be used for serialization: Should be accessible in each worker node
    val toBeBroadcasted = sc.broadcast(sc.union(
      sameAsTriplesExtracted,
      equivClassTriplesExtracted,
      equivPropertyTriplesExtracted,
      subClassOfTriplesExtracted
    ).collect())


    println("======================================")
    println("|              PACKAGE               |")
    println("======================================")
    serialzedPackage.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")


    val sameAsTriples = cachedRDDGraph
      .filter(t => t.o.isLiteral || (t.p.getURI == rdfTypeURI && t.o.toString() == typeOfEntityURI) || t.getPredicate.getURI == entityFragment)
      /* Maps to the tuple of subjects and objects */
      .map(t => (t.s, t.o))
      /* Group based on the Triple subject */
      .groupBy(_._1)
      /* serialized data based on the same keys */
      .map(t => t._1 -> t._2.map(_._2))

    logger.info("...Serialized data created " + (System.currentTimeMillis() - startTime) + "ms.")

    // Returns RDD[(Node, Iterable[Node, Node])]
    sameAsTriples.cache()
  }

}

object EREntitySerializerTest {


  def main(args: Array[String]) {
    // the SPARK config
    val conf = new SparkConf().setAppName("SPARK Reasoning")
    conf.set("spark.hadoop.validateOutputSpecs", "false")
    conf.setMaster("local[4]")
    conf.set("spark.eventLog.enabled", "true")
    val sc = new SparkContext(conf)

    // functional keys are provided by datasource experts
    val addressFunctionalKeysRULE2 = EREntitySerializerSemanticResolutionSet("http://datasource2.org/Location", "http://datasource2.org/inCity")
    // val functionalKeys = EREntitySerializerFunctionalProperties("http://datasource2.org/Location", "http://datasource2.org/inCity")


    val serializerTest = new EREntitySerializer(sc)
    val data = serializerTest.apply("ER/minDataMappingByExperts.ttl", "ER/sample2.ttl", addressFunctionalKeysRULE2)
    println("======================================")
    println("|        SERIALIZED TRIPLES          |")
    println("======================================")
    data.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")

    sc.stop()
  }
}