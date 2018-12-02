package net.sansa_stack.inference.spark.entityresolution

import net.sansa_stack.inference.spark.data.model.RDFGraph
import net.sansa_stack.inference.spark.data.writer.RDFGraphWriter
import net.sansa_stack.inference.spark.forwardchaining.triples.{ForwardRuleReasonerOWLHorst, ForwardRuleReasonerRDFS}
import org.apache.jena.graph.Triple
import org.apache.jena.rdf.model.ModelFactory
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable

class EREntitySerializer {

}

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
    while(iter.hasNext) {
      val st = iter.next()
      triples.add(st.asTriple())
    }


    val triplesRDD = sc.parallelize(triples.toSeq, 2)

    val graph = RDFGraph(triplesRDD)

    // create reasoner
    val reasoner = new ForwardRuleReasonerOWLHorst(sc)

    // compute inferred graph
    val inferredGraph = reasoner.apply(graph)

    // write triples to disk
    // RDFGraphWriter.writeToDisk(inferredGraph, args(0))
    RDFGraphWriter.writeToDisk(inferredGraph, "output/data/EREntitySerializer")


    sc.stop()
  }


}