package net.sansa_stack.inference.spark.rules.plan

import org.apache.jena.graph.Node

/**
  * A join between two triple patterns.
  *
  * @author Lorenz Buehmann
  */
case class Join(tp1: org.apache.jena.graph.Triple, tp2: org.apache.jena.graph.Triple, joinVar: Node) {
  override def toString: String = tp1.toString + " JOIN " + tp2.toString + " ON " + joinVar
}
