/**
 * Copyright 2011-2013 eBusiness Information, Groupe Excilys (www.excilys.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.excilys.ebi.gatling.core.session

import scala.reflect.ClassTag
import scala.util.Random

import com.excilys.ebi.gatling.core.util.{ FlattenableValidations, TypeHelper }

import scalaz.Scalaz.{ ToOptionOpsFromOption, ToValidationV }
import scalaz.Validation

trait Part[+T] {
	def resolve(session: Session): Validation[String, T]
}

case class StaticPart(string: String) extends Part[String] {
	def resolve(session: Session): Validation[String, String] = string.success
}

case class AttributePart(name: String) extends Part[Any] {
	def resolve(session: Session): Validation[String, Any] = session.safeGetAs[Any](name)
}

case class SeqSizePart(name: String) extends Part[Int] {
	def resolve(session: Session): Validation[String, Int] = session.safeGetAs[Seq[_]](name).map(_.size)
}

case class SeqRandomPart(name: String) extends Part[Any] {
	def resolve(session: Session): Validation[String,Any] = {
		def randomItem(seq: Seq[_]) = seq(Random.nextInt(seq.size))

		session.safeGetAs[Seq[_]](name).map(randomItem)
	}
}

case class SeqElementPart(name: String, index: String) extends Part[Any] {
	def resolve(session: Session): Validation[String, Any] = {

		def seqElementPart(index: Int): Validation[String, Any] = session.safeGetAs[Seq[_]](name).flatMap(_.lift(index).toSuccess(undefinedSeqIndexMessage(name, index)))

		try {
			val intIndex = index.toInt
			seqElementPart(intIndex)
		} catch {
			case e: NumberFormatException => session.safeGetAs[Int](index).flatMap(seqElementPart(_))
		}
	}
}

sealed abstract class ELParserException(message: String) extends Exception(message)
class ELMissingAttributeName(el: String) extends ELParserException("An attribute name is missing in this expression : " + el)
class ELNestedAttributeDefinition(el: String) extends ELParserException("There is a nested attribute definition in this expression : " + el)

object EL {

	val elPattern = """\$\{(.*?)\}""".r
	val elSeqSizePattern = """(.+?)\.size""".r
	val elSeqRandomPattern = """(.+?)\.random""".r
	val elSeqElementPattern = """(.+?)\((.+)\)""".r

	def wrap[T](value: T) = (session: Session) => value.success

	def compile[T: ClassTag](elString: String): Expression[T] = {

		def parse[T: ClassTag](string: String): List[Part[Any]] = {

			val staticParts = elPattern.split(string).map(StaticPart(_)).toList

			val dynamicParts = elPattern
				.findAllIn(string)
				.matchData
				.map {
					_.group(1) match {
						case elSeqElementPattern(key, occurrence) => SeqElementPart(key, occurrence)
						case elSeqSizePattern(key) => SeqSizePart(key)
						case elSeqRandomPattern(key) => SeqRandomPart(key)
						case key if key contains "${" => throw new ELNestedAttributeDefinition(string)
						case key if key.isEmpty => throw new ELMissingAttributeName(string)
						case key => AttributePart(key)
					}
				}
				.toList

			val indexedStaticParts = staticParts.zipWithIndex.map { case (part, index) => (part, index * 2) }.filter { case (part, index) => !part.string.isEmpty }
			val indexedDynamicParts = dynamicParts.zipWithIndex.map { case (part, index) => (part, index * 2 + 1) }

			(indexedStaticParts ::: indexedDynamicParts).sortBy(_._2).map(_._1)
		}

		parse(elString) match {
			case List(StaticPart(string)) => (session: Session) => TypeHelper.as[T](string)
			case List(dynamicPart) => dynamicPart.resolve _ andThen (_.flatMap(TypeHelper.as[T](_)))
			case parts => (session: Session) =>
				val resolvedString = parts.map(_.resolve(session))
					.flattenIt
					.map(_.mkString)

				resolvedString.flatMap(TypeHelper.as[T](_))
		}
	}
}
