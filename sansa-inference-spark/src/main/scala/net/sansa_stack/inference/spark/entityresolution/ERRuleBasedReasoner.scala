package net.sansa_stack.inference.spark.entityresolution

import net.sansa_stack.inference.spark.data.model.RDFGraph
import net.sansa_stack.inference.spark.forwardchaining.triples.ForwardRuleReasonerOWLHorst
import org.apache.jena.graph.{Node, Triple}
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.{OWL2, RDF}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.slf4j.LoggerFactory

import scala.collection.mutable


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
class ERRuleBasedReasoner(sc: SparkContext) {

  private val logger = com.typesafe.scalalogging.Logger(LoggerFactory.getLogger(this.getClass.getName))


  def apply() {
    logger.info("Serialisation has been started: convert the data into pair(key, list(vals))...")

  }






}
