package net.sansa_stack.inference.spark.entityresolution

import net.sansa_stack.inference.spark.data.model.RDFGraph
import net.sansa_stack.inference.spark.forwardchaining.triples.{ForwardRuleReasonerEL, ForwardRuleReasonerOWLHorst, ForwardRuleReasonerRDFS, TransitiveReasoner}
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.{OWL2, RDF, RDFS}
import org.apache.spark.{SparkConf, SparkContext}
import net.sansa_stack.inference.spark.data.model.TripleUtils._
import net.sansa_stack.inference.utils.CollectionUtils
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


  def apply(minManualInferencePath: String, dataPath: String, functionalKeys: EREntitySerializerSemanticResolutionSet, inferredSameAsTriples: RDD[Triple] = null): RDD[Triple] = {
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
    // Inferred sameAs triples from previous step
    if(inferredSameAsTriples != null) {triples ++= inferredSameAsTriples.collect()}
    val triplesRDD = sc.parallelize(triples.toSeq, parallelism)

    val graph = RDFGraph(triplesRDD)

    // create reasoner
    val reasoner = new ForwardRuleReasonerOWLHorst(sc)

    // compute inferred graph
    val inferredGraph = reasoner.apply(graph)
    val cachedRDDGraph = inferredGraph.triples.cache()
    println("======================================")
    println("|          INFERRED TRIPLES          |")
    println("======================================")
    cachedRDDGraph.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")


    val functionalEntityFragments = cachedRDDGraph
      .filter(t => t.o.isLiteral || (t.predicateMatches(RDF.`type`.asNode()) && t.o.getURI == typeOfEntityURI) || t.p.getURI == entityFragment)

    /*    val functionalEntities = cachedRDDGraph
          .filter(t => t.o.isLiteral || (t.predicateMatches(RDF.`type`.asNode()) && t.o.getURI == typeOfEntityURI) || t.p.getURI == entityFragment)
          .map(t => t.s.toString())
          .distinct()
    */
    println("======================================")
    println("|     FUNCTIONAL RELATED TRIPLES     |")
    println("======================================")
    functionalEntityFragments.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")


    val objectURIsFragment = functionalEntityFragments
      .filter(t => !t.o.isLiteral)
      .map(t => t.o.getURI)
    val sameAsTriplesList = cachedRDDGraph
      .filter(t => t.p == OWL2.sameAs.asNode)
    val equivalentClassTriplesList = cachedRDDGraph
      .filter(t => t.p == OWL2.equivalentClass.asNode)

    val equivTriples = cachedRDDGraph
      .filter(t => t.p == OWL2.equivalentClass.asNode)
      .map(t => (t.s, t.o))


   /* println("heeeeerrrreeeeeee")
    // TODO: the BC properties should be used for serialization: Should be accessible in each worker node
    val equiClassOfMap = CollectionUtils.toMultiMap(equivTriples.map(t => (t._1, t._2)).collect)
    val equiClassOfMapBC = sc.broadcast(equiClassOfMap) */




    // STEP 1: Entity Serialization
    // These triples should be broadcasted
    val serializedPackage = sc.union(functionalEntityFragments.distinct(),
      sameAsTriplesList.distinct(),
      equivalentClassTriplesList.distinct())
    val serializedPackageBC = sc.broadcast(serializedPackage.collect())


    println("======================================")
    println("|      ENTITY SERIALIZATION BC       |")
    println("======================================")
    serializedPackage.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")


    val serializerStep = cachedRDDGraph
      .filter(t => t.o.isLiteral || (t.p.getURI == rdfTypeURI && t.o.toString() == typeOfEntityURI) || t.getPredicate.getURI == entityFragment)
      /* Maps to the tuple of subjects and objects */
      .map(t => (t.o, t.s))
      /* Group based on the Triple subject */
      .groupBy(_._1)
      /* serialized data based on the same keys */
      .map(t => t._1 -> t._2.map(_._2))
    serializerStep.collect().foreach(println)


    val sameAsFinder = serializerStep.map { t =>
      val functionalVar = t._1
      val sameAsNodes = t._2
      (sameAsNodes, 1)
    }.reduceByKey((x, y) => x + y)

    sameAsFinder.collect().foreach(println)

    // returns entities that share the properties together with high probability
    val sameAsTripleEmitter = sameAsFinder.filter(t => t._2 >= 2).map(t => t._1)
      .flatMap { entitiy =>
        for (x <- entitiy; y <- entitiy)
          yield (Triple.create(x, OWL2.sameAs.asNode(), y))
      }

    println("======================================")
    println("|      INFERRED sameAs TRIPLES       |")
    println("======================================")
    sameAsTripleEmitter.collect().foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")

    logger.info("...Serialized data created " + (System.currentTimeMillis() - startTime) + "ms.")

    // Return sameAs Triples
    sameAsTripleEmitter
  }

}

object EREntitySerializerTest {


  def main(args: Array[String]) {
    // the SPARK config
    val conf = new SparkConf().setAppName("SPARK ER Reasoning")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.hadoop.validateOutputSpecs", "false")
    conf.setMaster("spark://172.18.160.16:3077")
    conf.set("spark.eventLog.enabled", "true")
    val sc = new SparkContext(conf)

    // functional keys are provided by datasource experts
    val addressFunctionalKeysRULE2 = EREntitySerializerSemanticResolutionSet("http://datasource2.org/Location", "http://datasource2.org/inCity")
    // This test case also works flawlessly
    val addressFunctionalKeysRULE1 = EREntitySerializerSemanticResolutionSet("http://datasource1.org/Address", "http://datasource2.org/inCity")

    // Test for Houses
    // val addressFunctionalKeysRULE1 = EREntitySerializerSemanticResolutionSet("http://datasource2.org/Housing", "http://datasource1.org/Located")


    val serializerTest = new EREntitySerializer(sc)
    // val inferredR2 = serializerTest.apply("ER/minDataMappingByExperts.ttl", "ER/sample2.ttl", addressFunctionalKeysRULE2, null)
    // val inferredR1 = serializerTest.apply("ER/minDataMappingByExperts.ttl", "ER/sample2.ttl", addressFunctionalKeysRULE1, inferredR2)
    val minMappingURI = "hdfs://172.18.160.17:54310/MohammadaliGhasemi/ER/minDataMappingByExperts.ttl"
    val sampleDataURI = "hdfs://172.18.160.17:54310/MohammadaliGhasemi/ER/sample2.ttl"
    val inferredR2 = serializerTest.apply(minMappingURI, sampleDataURI, addressFunctionalKeysRULE2, null)
    val inferredR1 = serializerTest.apply(minMappingURI, sampleDataURI, addressFunctionalKeysRULE1, inferredR2)

    println("======================================")
    println("|        SERIALIZED TRIPLES          |")
    println("======================================")
    inferredR1.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")

    sc.stop()
  }
}