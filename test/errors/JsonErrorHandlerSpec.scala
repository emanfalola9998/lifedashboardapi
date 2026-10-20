package errors

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.{Environment, Mode}
import play.api.test.FakeRequest
import play.api.test.Helpers.*

class JsonErrorHandlerSpec extends AnyWordSpec with Matchers {

  private def handler(mode: Mode) = new JsonErrorHandler(Environment.simple(mode = mode))
  private val request             = FakeRequest("POST", "/dashboard/save/user-1")

  "onClientError" should {
    "return JSON with the original status and message" in {
      val result = handler(Mode.Prod).onClientError(request, BAD_REQUEST, "Invalid Json")
      status(result) shouldBe BAD_REQUEST
      contentType(result) shouldBe Some("application/json")
      (contentAsJson(result) \ "error").as[String] shouldBe "Invalid Json"
    }
    "fall back to a generic message when Play gives an empty one" in {
      val result = handler(Mode.Prod).onClientError(request, NOT_FOUND, "")
      (contentAsJson(result) \ "error").as[String] shouldBe "Not found"
    }
  }

  "onServerError" should {
    val boom = new RuntimeException("relation \"dashboard_data\" does not exist")

    "hide the exception message in production" in {
      val result = handler(Mode.Prod).onServerError(request, boom)
      status(result) shouldBe INTERNAL_SERVER_ERROR
      contentType(result) shouldBe Some("application/json")
      (contentAsJson(result) \ "error").as[String] shouldBe "Internal server error"
    }
    "show the exception message in dev" in {
      val result = handler(Mode.Dev).onServerError(request, boom)
      (contentAsJson(result) \ "error").as[String] should include("dashboard_data")
    }
    "include the request id so logs and responses can be matched up" in {
      val result = handler(Mode.Prod).onServerError(request, boom)
      (contentAsJson(result) \ "requestId").as[Long] shouldBe request.id
    }
  }
}
