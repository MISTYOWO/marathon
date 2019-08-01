package mesosphere.marathon
package integration.facades

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{Get, Post}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import mesosphere.marathon.integration.setup.RestResult
import mesosphere.marathon.integration.setup.AkkaHttpResponse._
import play.api.libs.json.{JsObject, Json}

import scala.collection.immutable.Seq
import scala.concurrent.Await._
import scala.concurrent.duration._

object MesosFacade {

  /**
    * Corresponds to parts of `state` JSON response.
    */
  case class ITMesosState(
      version: String,
      gitTag: Option[String],
      agents: Seq[ITAgent],
      frameworks: Seq[ITFramework],
      completed_frameworks: Seq[ITFramework],
      unregistered_framework_ids: Seq[String])

  case class ITAgent(
      id: String,
      attributes: ITAttributes,
      resources: ITResources,
      usedResources: ITResources,
      offeredResources: ITResources,
      reservedResourcesByRole: Map[String, ITResources],
      unreservedResources: ITResources)

  case class ITAttributes(attributes: Map[String, ITResourceValue])

  object ITAttributes {
    def empty: ITAttributes = new ITAttributes(Map.empty)

    def apply(vals: (String, Any)*): ITAttributes = {
      val attributes: Map[String, ITResourceValue] = vals.map {
        case (id, value: Double) => id -> ITResourceScalarValue(value)
        case (id, value: String) => id -> ITResourceStringValue(value)
        case (id, value: Any) => throw new IllegalArgumentException(s"Unexpected attribute id=$id value=$value")
      }(collection.breakOut)
      ITAttributes(attributes)
    }
  }

  case class ITResources(resources: Map[String, ITResourceValue]) {
    def isEmpty: Boolean = resources.isEmpty || resources.values.forall(_.isEmpty)

    def nonEmpty: Boolean = !isEmpty

    override def toString: String = {
      "{" + resources.toSeq.sortBy(_._1).map {
        case (k, v) => s"$k: $v"
      }.mkString(", ") + " }"
    }
  }

  object ITResources {
    def empty: ITResources = new ITResources(Map.empty)

    def apply(vals: (String, Any)*): ITResources = {
      val resources: Map[String, ITResourceValue] = vals.map {
        case (id, value: Double) => id -> ITResourceScalarValue(value)
        case (id, value: String) => id -> ITResourceStringValue(value)
        case (id, value) => throw new IllegalStateException(s"Unsupported ITResource type: ${value.getClass}; expected: Double | String")
      }(collection.breakOut)
      ITResources(resources)
    }
  }

  sealed trait ITResourceValue {
    def isEmpty: Boolean
  }

  case class ITResourceScalarValue(value: Double) extends ITResourceValue {
    override def isEmpty: Boolean = value == 0

    override def toString: String = value.toString
  }

  case class ITResourceStringValue(portString: String) extends ITResourceValue {
    override def isEmpty: Boolean = false

    override def toString: String = '"' + portString + '"'
  }

  case class ITask(id: String, status: Option[String])

  case class ITFramework(id: String, name: String, tasks: Seq[ITask], unreachable_tasks: Seq[ITask])

  case class ITFrameworks(
      frameworks: Seq[ITFramework],
      completed_frameworks: Seq[ITFramework],
      unregistered_frameworks: Seq[ITFramework])

}

class MesosFacade(val url: String, val waitTime: FiniteDuration = 30.seconds)(implicit val system: ActorSystem, materializer: Materializer)
  extends PlayJsonSupport with StrictLogging {

  import MesosFacade._
  import MesosFormats._
  import system.dispatcher

  // `waitTime` is passed implicitly to the `request` and `requestFor` methods
  implicit val requestTimeout = waitTime

  def state: RestResult[ITMesosState] = {
    logger.info(s"fetching state from $url")
    result(requestFor[ITMesosState](Get(s"$url/state")), waitTime)
  }

  def frameworks(): RestResult[ITFrameworks] = {
    result(requestFor[ITFrameworks](Get(s"$url/frameworks")), waitTime)
  }

  def frameworkIds(): RestResult[Seq[String]] = {
    frameworks().map(_.frameworks.map(_.id))
  }

  def completedFrameworkIds(): RestResult[Seq[String]] = {
    frameworks().map(_.completed_frameworks.map(_.id))
  }

  def teardown(frameworkId: String): HttpResponse = {
    result(request(Post(s"$url/teardown", HttpEntity(s"frameworkId=$frameworkId"))), waitTime).value
  }

  /**
    * Mark agent as gone using v1 operator API
    */
  def markAgentGone(agentId: String): RestResult[HttpResponse] = {
    result(request(Post(s"$url/api/v1", Json.obj(
      "type" -> "MARK_AGENT_GONE",
      "mark_agent_gone" -> Json.obj(
        "agent_id" -> Json.obj("value" -> agentId))))), waitTime)
  }

  /**
    * Drain agent using v1 operator API
    */
  def drainAgent(agentId: String, maxGracePeriod: Option[Int] = None, markAsGone: Boolean = false): RestResult[HttpResponse] = {
    var requestPayload = Json.obj(
      "type" -> "DRAIN_AGENT",
      "drain_agent" -> Json.obj(
        "agent_id" -> Json.obj("value" -> agentId),
        // TODO: not sure whether is expected to be an object or a boolean field
        "mark_gone" -> Json.obj("value" -> markAsGone)
      )
    )
    maxGracePeriod.foreach { duration =>
      requestPayload = requestPayload ++ Json.obj(
        "max_grace_period" -> Json.obj("value" -> duration)
      )
    }

    result(request(Post(s"$url/api/v1", requestPayload)), waitTime)
  }
}
