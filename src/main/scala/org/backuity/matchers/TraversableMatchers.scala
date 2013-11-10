package org.backuity.matchers

import scala.annotation.tailrec
import org.backuity.matchers.TraversableMatchers.ContainError
import scala.util.control.Breaks


trait TraversableMatchers extends CoreMatcherSupport {

   def forAll[T](m : Matcher[T]) = new EagerMatcher[Traversable[T]] {
    def description = s"for all ${m.description}"

    def eagerCheck(elems : Traversable[T]) {
      for( elem <- elems ) {
        try {
          m.check(elem)
        } catch {
          case util.control.NonFatal(e) => fail(s"$elems is not valid: ${e.getMessage}")
        }
      }
    }
  }

  /**
   * Valid if all the matchers are contained.
   * @note an element might satisfy multiple matchers, it is the caller responsibility to not pass overlapping matchers
   */
  def contain[T](matchers: Matcher[T]*) = new EagerMatcher[Traversable[T]] {
    def description = s"contain ${matchers.map(_.description).mkString(", ")}"

    def eagerCheck(elems: Traversable[T]) {
      checkAnElementForEveryMatcher(matchers, elems)
    }
  }

  def containAny[T](matchers: Matcher[T]*) = new EagerMatcher[Traversable[T]] {
    def description = s"contain any of ${matchers.map(_.description).mkString(", ")}"

    protected def eagerCheck(t: Traversable[T]) {
      import Breaks._
      breakable {
        val errors = for( matcher <- matchers ) yield {
          checkAnElementForAMatcher(matcher, t.toSeq) match {
            case None => break()
            case Some(err) => err
          }
        }
        failFor(s"$t does not contain any of", errors)
      }
    }
  }

  /** elements might be duplicated */
  def containElements[T](others: T*)(implicit formatter: Formatter[T]) = new EagerMatcher[Traversable[T]] {
    def description = "contain the same elements as " + others

    protected def eagerCheck(elems: Traversable[T]) {
      val missingElements: Seq[T] = others.toSeq.diff(elems.toSeq)
      val extraElements : Seq[T] = elems.toSeq.diff(others.toSeq)

      val missingMsg : String = if( missingElements.isEmpty ) "" else s"does not contain ${formatter.formatAll(missingElements)}"
      val extraMsg : String = if( extraElements.isEmpty ) "" else s"contains unexpected elements ${formatter.formatAll(extraElements)}"
      val article : String = if( missingElements.isEmpty || extraElements.isEmpty ) "" else " but "

      failIf( !missingElements.isEmpty || !extraElements.isEmpty, s"$elems $missingMsg$article$extraMsg")
    }
  }

  /**
   * Valid if the sizes match and there is
   *   - for each element a satisfied matcher
   *   - for each matcher an element satisfying it
   *
   * Things like these are therefore accepted:
   * {{{
   *   3 elements: e1, e2, e3
   *   3 matchers: m1, m2, m3
   *
   *   e1 matches m1
   *   e2 matches m1
   *   e3 matches m2, m3
   * }}}
   *
   * It is the caller responsibility to not pass overlapping matchers.
   */
  def containExactly[T](matchers: Matcher[T]*) = new EagerMatcher[Traversable[T]] {
    def description = s"contain exactly (${matchers.map(_.description).mkString(", ")})"

    def eagerCheck(elems: Traversable[T]) {

      val tooFewTooMany = if( elems.size < matchers.size ) Some("too few") else if( elems.size > matchers.size ) Some("too many") else None
      val sizeErrorMessage = tooFewTooMany.map( " has " + _ + s" elements, expected ${matchers.size}, got ${elems.size}")
      def failPrefix = sizeErrorMessage.map( _ + ";" ).getOrElse("")

      checkAMatcherForEveryElement(elems, failPrefix)
      checkAnElementForEveryMatcher(matchers, elems, failPrefix)

      for( sizeError <- sizeErrorMessage ) {
        fail( elems + sizeError )
      }
    }


    def checkAMatcherForEveryElement(elems: Traversable[T], failPrefix : String) {

      // stop at the first successful matcher and return Nil
      // return the error messages otherwise
      @tailrec
      def checkMatchers(elem: T, errors: List[String], matchers: Seq[Matcher[T]]) : List[String] = {
        matchers match {
          case matcher +: remainingMatchers =>
            try {
              matcher.check(elem)
              Nil
            } catch {
              case e : Throwable =>
                var msg = e.getMessage
                if( msg.startsWith(elem.toString) ) {
                  msg = msg.substring(elem.toString.length)
                }
                msg = msg.trim
                checkMatchers(elem, errors :+ e.getMessage, remainingMatchers)
            }

          case empty => errors
        }
      }

      val failingElems = (for( elem <- elems ) yield {
        checkMatchers(elem, Nil, matchers) match {
          case Nil => None
          case errors => Some(new ContainError(elem.toString, errors))
        }
      }).flatten

      failFor(elems + failPrefix + " has unexpected elements", failingElems)
    }
  }

  /**
   * Stop at the first successful element
   * @return Some(ContainError) if no element match matcher, None otherwise
   */
  private def checkAnElementForAMatcher[T](matcher: Matcher[T], elems: Traversable[T]) : Option[ContainError] = {
    if( elems.isEmpty ) {
      Some(new ContainError(matcher.description, Nil))
    } else {

      // stop at the first successful element and return Nil
      // return the error messages otherwise
      @tailrec
      def checkElems(matcher: Matcher[T], errors: List[String], elems: Seq[T]) : List[String] = {
        elems match {
          case elem +: remainingElems =>
            try {
              matcher.check(elem)
              Nil
            } catch {
              case e : Throwable =>
                val msg = e.getMessage
                checkElems(matcher, errors :+ msg, remainingElems)
            }

          case empty => errors
        }
      }

      checkElems(matcher, Nil, elems.toSeq) match {
        case Nil => None
        case errors => Some(new ContainError(matcher.description, errors))
      }
    }
  }

  private def checkAnElementForEveryMatcher[T](matchers: Seq[Matcher[T]], elems: Traversable[T], failPrefix : String = "")(implicit formatter: Formatter[Traversable[T]]) {
    val failingMatchers = (for( matcher <- matchers ) yield checkAnElementForAMatcher(matcher, elems.toSeq)).flatten

    failFor(formatter.format(elems) + failPrefix + " does not contain", failingMatchers)
  }

  private def failFor(what: String, failingElems: Traversable[ContainError]) {
    if( !failingElems.isEmpty ) {
      fail(what +
        (if( failingElems.size == 1 ) {
          " " + failingElems.mkString
        } else {
          ":\n- " + failingElems.mkString("\n- ")
        }))
    }
  }
}

object TraversableMatchers {

  /**
   * @param errors can be empty
   */
  private class ContainError(val item: String, val errors: List[String]) {
    override def toString = {
      errors match {
        case Nil => item
        case hd :: Nil =>
          if( hd.startsWith(item)) {
            hd
          } else {
            item + " : " + hd
          }

        case _ =>
          val trimedErrors = errors.map{ e =>
            (if( e.startsWith(item) ) {
              e.substring(item.length)
            } else e).trim
          }
          item + " :\n  * " + trimedErrors.mkString("\n  * ")
      }
    }
  }
}