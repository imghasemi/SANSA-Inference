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
import net.sansa_stack.rdf.spark.stats._

import org.apache.spark.sql.SparkSession
import java.net.URI
import net.sansa_stack.rdf.spark.io._
import org.apache.jena.riot.Lang
import scala.collection.mutable
import java.io.File


// EXAMPLE: (?xi rdf:type ds1:Location ) ∧ (?yj rdf:type ds1:Location ) ∧ (?xi ds2:street ?si) ∧ (?yj ds2:street ?sj)∧(?si owl:sameAs ?sj) ⇒ (?xi owl:sameAs ?yj)
// (Semantic entity resolution).
// Semantic entity resolution is based on a functional key that includes the property rdf : type.
// That is, given functional key fk = {p1, ..., pn} + all URIs
// and two entity fragments EFi and EFj
case class EREntitySerializerSemanticResolutionSet(typeOfEntityURI: String, entityFragment: String)

class EREntitySerializer(sc: SparkContext, parallelism: Int = 2) extends TransitiveReasoner(sc, parallelism) {
  private val logger = com.typesafe.scalalogging.Logger(LoggerFactory.getLogger(this.getClass.getName))


  def apply(data: RDD[Triple], functionalKeys: EREntitySerializerSemanticResolutionSet, inferredSameAsTriples: RDD[Triple] = null): RDD[Triple] = {
    logger.warn("Serialization has been started...")
    val typeOfEntityURI = functionalKeys.typeOfEntityURI
    val entityFragment = functionalKeys.entityFragment
    // val entityFragment2 = functionalKeys.entityFragment2
    val rdfTypeURI = RDF.`type`.getURI

    // @deprecated closure function
    def filterFunctionalKeysTriples(t: Triple): Boolean = {
      t.o.isLiteral || (t.predicateMatches(RDF.`type`.asNode()) && t.o.getURI == typeOfEntityURI) || t.p.getURI == entityFragment
    }

    val startTime = System.currentTimeMillis()

    //    // Inferred sameAs triples from previous step
    if (inferredSameAsTriples != null) {
      data.union(inferredSameAsTriples)
    }
    //    val triplesRDD = sc.parallelize(data, parallelism)

    val graph = RDFGraph(data)

    // create reasoner
    val reasoner = new ForwardRuleReasonerOWLHorst(sc)

    // compute inferred graph
    val inferredGraph = reasoner.apply(graph)
    val cachedRDDGraph = inferredGraph.triples
    println("======================================")
    println("|          INFERRED TRIPLES          |")
    println("======================================")
    // cachedRDDGraph.foreach(println)
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
    // TODO: Eliminate too many collect() to prevent the OutOfMemory buffer
    // functionalEntityFragments.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")

    // TODO: Eliminate too many collect() to prevent the OutOfMemory buffer
    //    val objectURIsFragment = functionalEntityFragments
    //      .filter(t => !t.o.isLiteral)
    //      .map(t => t.o.getURI)
    //    val sameAsTriplesList = cachedRDDGraph
    //      .filter(t => t.p == OWL2.sameAs.asNode)
    //    val equivalentClassTriplesList = cachedRDDGraph
    //      .filter(t => t.p == OWL2.equivalentClass.asNode)

    //    val equivTriples = cachedRDDGraph
    //      .filter(t => t.p == OWL2.equivalentClass.asNode)
    //      .map(t => (t.s, t.o))


    // TODO: Eliminate too many collect() to prevent the OutOfMemory buffer
    // STEP 1: Entity Serialization
    // These triples should be broadcasted
    //    val serializedPackage = sc.union(functionalEntityFragments.distinct(),
    //      sameAsTriplesList.distinct(),
    //      equivalentClassTriplesList.distinct())
    //    val serializedPackageBC = sc.broadcast(serializedPackage.collect())


    println("======================================")
    println("|      ENTITY SERIALIZATION BC       |")
    println("======================================")
    //    serializedPackage.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")


    val serializerStep = cachedRDDGraph
      .filter(t => t.o.isLiteral || (t.p.getURI == rdfTypeURI && t.o.toString() == typeOfEntityURI) || t.getPredicate.getURI == entityFragment)
      /* Maps to the tuple of subjects and objects */
      .map(t => (t.o.toString(), t.s))
      /* Group based on the objects */
      .groupBy(_._1)
      /* serialized data based on the same functional keys */
      .map(t => t._1 -> t._2.map(_._2))
    //  (http://datasource2.org/Location,List(http://datasource1.org/ad03, http://datasource2.org/ad25))
    // TODO: Eliminate too many collect() to prevent the OutOfMemory buffer
    // serializerStep.collect().foreach(println)


    val sameAsFinder = serializerStep.map { t =>
      val functionalVar = t._1
      val sameAsNodes = t._2
      (sameAsNodes, 1)
    }.reduceByKey((x, y) => x + y)

    // TODO: Eliminate too many collect() to prevent the OutOfMemory buffer
    // sameAsFinder.collect().foreach(println)

    // returns entities that share the properties together with high probability
    val threshold = 2
    val sameAsTripleEmitter = sameAsFinder.filter(t => t._2 >= threshold).map(t => t._1)
      .flatMap { entitiy =>
        for (x <- entitiy; y <- entitiy)
          yield Triple.create(x, OWL2.sameAs.asNode(), y)
      }

    println("======================================")
    println("|      INFERRED sameAs TRIPLES       |")
    println("======================================")
    // sameAsTripleEmitter.collect().foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")

    logger.warn(".........Serialized data created " + (System.currentTimeMillis() - startTime) + "ms.")

    // Return sameAs Triples
    sameAsTripleEmitter
  }
}

object EREntitySerializerTest {
  private val logger = com.typesafe.scalalogging.Logger(LoggerFactory.getLogger(this.getClass.getName))


  def main(args: Array[String]) {

    // the SPARK config
    //    val conf = new SparkConf().setAppName("SPARK ER Reasoning")
    //    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    //    conf.set("spark.hadoop.validateOutputSpecs", "false")
    //    conf.setMaster("spark://172.18.160.16:3090")
    //    conf.set("spark.eventLog.enabled", "true")
    //    val sc = new SparkContext(conf)

    logger.warn("ER has been started...")
    val startTime = System.currentTimeMillis()

    val spark = SparkSession.builder
      .appName("ER Reasoning")
      .master("spark://172.18.160.16:3090")
      // .master("local[*]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryoserializer.buffer.max.mb", "512")
      .config("spark.memory.offHeap.enabled", true)
      .config("spark.memory.offHeap.size", "5g")
      .getOrCreate()


    val addressFunctionalKeysRULE2 = EREntitySerializerSemanticResolutionSet("http://datasource2.org/Location", "http://datasource2.org/inCity")
    val addressFunctionalKeysRULE1 = EREntitySerializerSemanticResolutionSet("http://datasource1.org/Address", "http://datasource2.org/inCity")

    val serializerTest = new EREntitySerializer(spark.sparkContext)

    val sampleDataURI = "hdfs://172.18.160.17:54310/MohammadaliGhasemi/BSBM_500MB.nt"
    val dataTriples = spark.rdf(Lang.NTRIPLES)(sampleDataURI)


    val inferredR2 = serializerTest.apply(dataTriples, addressFunctionalKeysRULE2, null)

    println("======================================")
    println("|        SERIALIZED TRIPLES          |")
    println("======================================")
    inferredR2.foreach(println)
    println("======================================")
    println("|                 END                |")
    println("======================================")
    logger.warn("ER FINISHED at: " + (System.currentTimeMillis() - startTime) + "ms.")
    spark.stop()
  }
}
