package mesosphere.marathon
package api.v2

import java.io.InputStream
import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.MediaType

import mesosphere.marathon.api.RestResource
import scala.concurrent.ExecutionContext

@Path("v2/schemas")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class SchemaResource @Inject() (
    val config: MarathonConf)(implicit val executionContext: ExecutionContext) extends RestResource {

  //TODO: schemas are available via /public/api/v2/schema/* anyway
  @GET
  @Path("app")
  def index(): InputStream = {
    getClass.getResourceAsStream("/public/api/v2/schema/AppDefinition.json")
  }
}
