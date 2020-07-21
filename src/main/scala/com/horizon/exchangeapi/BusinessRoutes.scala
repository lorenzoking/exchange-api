/** Services routes for all of the /orgs/{orgid}/business api methods. */
package com.horizon.exchangeapi

import java.sql.Timestamp

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth._
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

import scala.concurrent.ExecutionContext
import com.horizon.exchangeapi.tables._
import org.json4s.{DefaultFormats, Formats}
//import org.json4s._
import org.json4s.jackson.Serialization.write
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.util._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/business/policies routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/business/policies */
final case class GetBusinessPoliciesResponse(businessPolicy: Map[String,BusinessPolicy], lastIndex: Int)
final case class GetBusinessPolicyAttributeResponse(attribute: String, value: String)

object BusinessUtils {
  def getAnyProblem(service: BService): Option[String] = {
    // Check they specified at least 1 service version
    if (service.serviceVersions.isEmpty) return Some(ExchMsg.translate("no.version.specified.for.service2"))
    // Check the version syntax
    for (sv <- service.serviceVersions) {
      if (!Version(sv.version).isValid) return Some(ExchMsg.translate("version.not.valid.format", sv.version))
    }
    return None
  }
}

/** Input format for POST/PUT /orgs/{orgid}/business/policies/<bus-pol-id> */
final case class PostPutBusinessPolicyRequest(label: String, description: Option[String], service: BService, userInput: Option[List[OneUserInputService]], properties: Option[List[OneProperty]], constraints: Option[List[String]]) {
  require(label!=null && service!=null && service.name!=null && service.org!=null && service.arch!=null && service.serviceVersions!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = { BusinessUtils.getAnyProblem(service) }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { BusinessPoliciesTQ.validateServiceIds(service, userInput.getOrElse(List())) }

  // The nodeHealth field is optional, so fill in a default in service if not specified. (Otherwise json4s will omit it in the DB and the GETs.)
  def defaultNodeHealth(service: BService): BService = {
    if (service.nodeHealth.nonEmpty) return service
    val hbDefault = ExchConfig.getInt("api.defaults.businessPolicy.missing_heartbeat_interval")
    val agrChkDefault = ExchConfig.getInt("api.defaults.businessPolicy.check_agreement_status")
    val nodeHealth2 = Some(Map("missing_heartbeat_interval" -> hbDefault, "check_agreement_status" -> agrChkDefault)) // provide defaults for node health
    return BService(service.name, service.org, service.arch, service.serviceVersions, nodeHealth2)
  }

  // Note: write() handles correctly the case where the optional fields are None.
  def getDbInsert(businessPolicy: String, orgid: String, owner: String): DBIO[_] = {
    BusinessPolicyRow(businessPolicy, orgid, owner, label, description.getOrElse(label), write(defaultNodeHealth(service)), write(userInput), write(properties), write(constraints), ApiTime.nowUTC, ApiTime.nowUTC).insert
  }

  def getDbUpdate(businessPolicy: String, orgid: String, owner: String): DBIO[_] = {
    BusinessPolicyRow(businessPolicy, orgid, owner, label, description.getOrElse(label), write(defaultNodeHealth(service)), write(userInput), write(properties), write(constraints), ApiTime.nowUTC, "").update
  }
}

final case class PatchBusinessPolicyRequest(label: Option[String], description: Option[String], service: Option[BService], userInput: Option[List[OneUserInputService]], properties: Option[List[OneProperty]], constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    /* if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else */ if (service.isDefined) BusinessUtils.getAnyProblem(service.get)
    else None
  }

  /** Returns a tuple of the db action to update parts of the businessPolicy, and the attribute name being updated. */
  def getDbUpdate(businessPolicy: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this businessPolicy
    label match { case Some(lab) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.label,d.lastUpdated)).update((businessPolicy, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.description,d.lastUpdated)).update((businessPolicy, desc, lastUpdated)), "description"); case _ => ; }
    service match { case Some(svc) => return ((for {d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.service,d.lastUpdated)).update((businessPolicy, write(svc), lastUpdated)), "service"); case _ => ; }
    userInput match { case Some(input) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.userInput,d.lastUpdated)).update((businessPolicy, write(input), lastUpdated)), "userInput"); case _ => ; }
    properties match { case Some(prop) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.properties,d.lastUpdated)).update((businessPolicy, write(prop), lastUpdated)), "properties"); case _ => ; }
    constraints match { case Some(con) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.constraints,d.lastUpdated)).update((businessPolicy, write(con), lastUpdated)), "constraints"); case _ => ; }
    return (null, null)
  }
}


/** Input for business policy-based search for nodes to make agreements with. */
final case class PostBusinessPolicySearchRequest(changedSince: Long = 0L,
                                                 nodeOrgids: Option[List[String]] = None,
                                                 numEntries: Option[Int] = None,
                                                 session: Long = -1L)

// Tried this to have names on the tuple returned from the db, but didn't work...
final case class BusinessPolicySearchHashElement(nodeType: String, publicKey: String, noAgreementYet: Boolean)

final case class BusinessPolicyNodeResponse(id: String, nodeType: String, publicKey: String)
final case class PostBusinessPolicySearchResponse(nodes: List[BusinessPolicyNodeResponse], offsetUpdated: Boolean = false)



/** Implementation for all of the /orgs/{orgid}/business/policies routes */
@Path("/v1/orgs/{orgid}/business/policies")
trait BusinessRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def businessRoutes: Route = busPolsGetRoute ~ busPolGetRoute ~ busPolPostRoute ~ busPolPutRoute ~ busPolPatchRoute ~ busPolDeleteRoute ~ busPolPostSearchRoute

  /* ====== GET /orgs/{orgid}/business/policies ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all business policies", description = "Returns all business policy definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include business policies with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include business policies with this owner (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include business policies with this label (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include business policies with this description (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "businessPolicy": {
    "orgid/mybuspol": {
      "owner": "string",
      "label": "string",
      "description": "string",
      "service": {
        "name": "string",
        "org": "string",
        "arch": "string",
        "serviceVersions": [
          {
            "version": "1.2.3",
            "priority": null,
            "upgradePolicy": null
          }
        ],
        "nodeHealth": {
          "missing_heartbeat_interval": 600,
          "check_agreement_status": 120
        }
      },
      "userInput": [],
      "properties": [
        {
          "name": "string",
          "type": "string",
          "value": "string"
        }
      ],
      "constraints": [
        "a == b"
      ],
      "lastUpdated": "string",
      "created": "string"
    },
      ...
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetBusinessPoliciesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "policy")
  def busPolsGetRoute: Route = (path("orgs" / Segment / "business" / "policies") & get & parameter(('idfilter.?, 'owner.?, 'label.?, 'description.?))) { (orgid, idfilter, owner, label, description) =>
    exchAuth(TBusiness(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      complete({
        var q = BusinessPoliciesTQ.getAllBusinessPolicies(orgid)
        // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
        idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.businessPolicy like id) else q = q.filter(_.businessPolicy === id) })
        owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
        label.foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
        description.foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })

        db.run(q.result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/business/policies result size: "+list.size)
          val businessPolicy = list.filter(e => ident.getOrg == e.orgid || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.businessPolicy -> e.toBusinessPolicy).toMap
          val code = if (businessPolicy.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetBusinessPoliciesResponse(businessPolicy, 0))
        })
      }) // end of complete
  } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/business/policies/{policy} ================================ */
  @GET
  @Path("{policy}")
  @Operation(summary = "Returns a business policy", description = "Returns the business policy with the specified id. Can be run by a user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name."),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire business policy resource will be returned.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "businessPolicy": {
    "orgid/mybuspol": {
      "owner": "string",
      "label": "string",
      "description": "string",
      "service": {
        "name": "string",
        "org": "string",
        "arch": "string",
        "serviceVersions": [
          {
            "version": "1.2.3",
            "priority": null,
            "upgradePolicy": null
          }
        ],
        "nodeHealth": {
          "missing_heartbeat_interval": 600,
          "check_agreement_status": 120
        }
      },
      "userInput": [],
      "properties": [
        {
          "name": "string",
          "type": "string",
          "value": "string"
        }
      ],
      "constraints": [
        "a == b"
      ],
      "lastUpdated": "string",
      "created": "string"
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetBusinessPoliciesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "policy")
  def busPolGetRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & get & parameter(('attribute.?))) { (orgid, policy, attribute) =>
    val compositeId = OrgAndId(orgid,policy).toString
    exchAuth(TBusiness(compositeId), Access.READ) { _ =>
      complete({
        attribute match {
          case Some(attribute) =>  // Only returning 1 attr of the businessPolicy
            val q = BusinessPoliciesTQ.getAttribute(compositeId, attribute) // get the proper db query for this attribute
            if (q == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.wrong.attribute", attribute)))
            else db.run(q.result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/business/policies/" + policy + " attribute result: " + list.toString)
              if (list.nonEmpty) {
                (HttpCode.OK, GetBusinessPolicyAttributeResponse(attribute, list.head.toString))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
              }
            })

          case None =>  // Return the whole business policy resource
            db.run(BusinessPoliciesTQ.getBusinessPolicy(compositeId).result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/business/policies result size: " + list.size)
              val businessPolicies = list.map(e => e.businessPolicy -> e.toBusinessPolicy).toMap
              val code = if (businessPolicies.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetBusinessPoliciesResponse(businessPolicies, 0))
            })
        }
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/business/policies/{policy} ===============================
  @POST
  @Path("{policy}")
  @Operation(
    summary = "Adds a business policy",
    description = "Creates a business policy resource. A business policy resource specifies the service that should be deployed based on the specified properties and constraints. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "policy",
        in = ParameterIn.PATH,
        description = "Business Policy name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """// (remove all of the comments like this before using)
{
  "label": "name of the business policy",  // this will be displayed in the UI
  "description": "descriptive text",
  // The services that this business policy applies to. (The services must exist before creating this business policy.)
  "service": {
    "name": "mydomain.com.weather",
    "org": "myorg",
    "arch": "amd64",                 // can be set to "*" or "" to mean all architectures
    // If multiple service versions are listed, Horizon will try to automatically upgrade nodes to the version with the lowest priority_value number
    "serviceVersions": [
      {
        "version": "1.0.1",
        "priority": {               // can be omitted
          "priority_value": 50,
          "retries": 1,
          "retry_durations": 3600,
          "verified_durations": 52
        },
        // When Horizon should upgrade nodes to newer service versions. Can be set to {} to take the default of immediate.
        "upgradePolicy": {           // can be omitted
          "lifecycle": "immediate",
          "time": "01:00AM"          // reserved for future use
        }
      }
    ],
    // If not using agbot node health check, this field can be set to {} or omitted completely.
    "nodeHealth": {                       // can be omitted
      "missing_heartbeat_interval": 600,  // How long a node heartbeat can be missing before cancelling its agreements (in seconds)
      "check_agreement_status": 120       // How often to check that the node agreement entry still exists, and cancel agreement if not found (in seconds)
    }
  },
  // Override or set user input variables that are defined in the services used by this business policy.
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",                          // omit or leave blank to mean all architectures
      "serviceVersionRange": "[0.0.0,INFINITY)",  // or omit to mean all versions
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"               // (optional) [boolean, float, int, list of strings, string, version]
    }
  ],
  "constraints": [
    "a == b"
  ]
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutBusinessPolicyRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource created - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "policy")
  def busPolPostRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & post & entity(as[PostPutBusinessPolicyRequest])) { (orgid, policy, reqBody) =>
    val compositeId = OrgAndId(orgid, policy).toString
    exchAuth(TBusiness(compositeId), Access.CREATE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies" + policy + " service validation: " + v)
              var invalidIndex = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break
                  }
                }
              }
              if (invalidIndex < 0) BusinessPoliciesTQ.getNumOwned(owner).result.asTry
              else {
                val errStr = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(num) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies" + policy + " num owned by " + owner + ": " + num)
              val numOwned = num
              val maxBusinessPolicies = ExchConfig.getInt("api.limits.maxBusinessPolicies")
              if (maxBusinessPolicies == 0 || numOwned <= maxBusinessPolicies) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                reqBody.getDbInsert(compositeId, orgid, owner).asTry
              }
              else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.buspols", maxBusinessPolicies))).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/business/policies/" + policy + " result: " + v)
              val policyChange = ResourceChangeRow(0L, orgid, policy, "policy", "false", "policy", ResourceChangeConfig.CREATED, ApiTime.nowUTCTimestamp)
              policyChange.insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies/" + policy + " updated in changes table: " + v)
              if (owner != "") AuthCache.putBusinessOwner(compositeId, owner) // currently only users are allowed to update business policy resources, so owner should never be blank
              AuthCache.putBusinessIsPublic(compositeId, isPublic = false)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.created", compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("buspol.already.exists", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.created", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.created", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/business/policies/{policy} ===============================
  @PUT
  @Path("{policy}")
  @Operation(summary = "Updates a business policy", description = "Updates a business policy resource. This can only be called by the user that created it.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name.")),
    requestBody = new RequestBody(description = "Business Policy object that needs to be updated. See details in the POST route above.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """// (remove all of the comments like this before using)
{
  "label": "name of the business policy",  // this will be displayed in the UI
  "description": "descriptive text",
  // The services that this business policy applies to. (The services must exist before creating this business policy.)
  "service": {
    "name": "mydomain.com.weather",
    "org": "myorg",
    "arch": "amd64",                 // can be set to "*" or "" to mean all architectures
    // If multiple service versions are listed, Horizon will try to automatically upgrade nodes to the version with the lowest priority_value number
    "serviceVersions": [
      {
        "version": "1.0.1",
        "priority": {               // can be omitted
          "priority_value": 50,
          "retries": 1,
          "retry_durations": 3600,
          "verified_durations": 52
        },
        // When Horizon should upgrade nodes to newer service versions. Can be set to {} to take the default of immediate.
        "upgradePolicy": {           // can be omitted
          "lifecycle": "immediate",
          "time": "01:00AM"          // reserved for future use
        }
      }
    ],
    // If not using agbot node health check, this field can be set to {} or omitted completely.
    "nodeHealth": {                       // can be omitted
      "missing_heartbeat_interval": 600,  // How long a node heartbeat can be missing before cancelling its agreements (in seconds)
      "check_agreement_status": 120       // How often to check that the node agreement entry still exists, and cancel agreement if not found (in seconds)
    }
  },
  // Override or set user input variables that are defined in the services used by this business policy.
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",                          // omit or leave blank to mean all architectures
      "serviceVersionRange": "[0.0.0,INFINITY)",  // or omit to mean all versions
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"               // (optional) [boolean, float, int, list of strings, string, version]
    }
  ],
  "constraints": [
    "a == b"
  ]
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutBusinessPolicyRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource created - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "policy")
  def busPolPutRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & put & entity(as[PostPutBusinessPolicyRequest])) { (orgid, policy, reqBody) =>
    val compositeId = OrgAndId(orgid, policy).toString
    exchAuth(TBusiness(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies" + policy + " service validation: " + v)
              var invalidIndex = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break
                  }
                }
              }
              if (invalidIndex < 0) reqBody.getDbUpdate(compositeId, orgid, owner).asTry
              else {
                val errStr = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/business/policies/" + policy + " result: " + n)
              val numUpdated = n.asInstanceOf[Int]     // i think n is an AnyRef so we have to do this to get it to an int
              if (numUpdated > 0) {
                if (owner != "") AuthCache.putBusinessOwner(compositeId, owner) // currently only users are allowed to update business policy resources, so owner should never be blank
                AuthCache.putBusinessIsPublic(compositeId, isPublic = false)
                val policyChange = ResourceChangeRow(0L, orgid, policy, "policy", "false", "policy", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTCTimestamp)
                policyChange.insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", compositeId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies/" + policy + " updated in changes table: " + v)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.updated", compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.updated", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.updated", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PATCH /orgs/{orgid}/business/policies/{policy} ===============================
  @PATCH
  @Path("{policy}")
  @Operation(summary = "Updates 1 attribute of a business policy", description = "Updates one attribute of a business policy. This can only be called by the user that originally created this business policy resource.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """// (remove all of the comments like this before using)
{
  "label": "name of the business policy",  // this will be displayed in the UI
  "description": "descriptive text",
  // The services that this business policy applies to. (The services must exist before creating this business policy.)
  "service": {
    "name": "mydomain.com.weather",
    "org": "myorg",
    "arch": "amd64",                 // can be set to "*" or "" to mean all architectures
    // If multiple service versions are listed, Horizon will try to automatically upgrade nodes to the version with the lowest priority_value number
    "serviceVersions": [
      {
        "version": "1.0.1",
        "priority": {               // can be omitted
          "priority_value": 50,
          "retries": 1,
          "retry_durations": 3600,
          "verified_durations": 52
        },
        // When Horizon should upgrade nodes to newer service versions. Can be set to {} to take the default of immediate.
        "upgradePolicy": {           // can be omitted
          "lifecycle": "immediate",
          "time": "01:00AM"          // reserved for future use
        }
      }
    ],
    // If not using agbot node health check, this field can be set to {} or omitted completely.
    "nodeHealth": {                       // can be omitted
      "missing_heartbeat_interval": 600,  // How long a node heartbeat can be missing before cancelling its agreements (in seconds)
      "check_agreement_status": 120       // How often to check that the node agreement entry still exists, and cancel agreement if not found (in seconds)
    }
  },
  // Override or set user input variables that are defined in the services used by this business policy.
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",                          // omit or leave blank to mean all architectures
      "serviceVersionRange": "[0.0.0,INFINITY)",  // or omit to mean all versions
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"               // (optional) [boolean, float, int, list of strings, string, version]
    }
  ],
  "constraints": [
    "a == b"
  ]
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutBusinessPolicyRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "policy")
  def busPolPatchRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & patch & entity(as[PatchBusinessPolicyRequest])) { (orgid, policy, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgid/business/policies/$policy")
    val compositeId = OrgAndId(orgid, policy).toString
    exchAuth(TBusiness(compositeId), Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val (action, attrName) = reqBody.getDbUpdate(compositeId, orgid)
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.buspol.attribute.specified")))
          else {
            val (valServiceIdActions, svcRefs) =
              if (attrName == "service") BusinessPoliciesTQ.validateServiceIds(reqBody.service.get, List())
              else if (attrName == "userInput") BusinessPoliciesTQ.validateServiceIds(BService("", "", "", List(), None), reqBody.userInput.get)
              else (DBIO.successful(Vector()), Vector())
            db.run(valServiceIdActions.asTry.flatMap({
              case Success(v) =>
                logger.debug("PUT /orgs/" + orgid + "/business/policies" + policy + " service validation: " + v)
                var invalidIndex = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                breakable {
                  for ((len, index) <- v.zipWithIndex) {
                    if (len <= 0) {
                      invalidIndex = index
                      break
                    }
                  }
                }
                if (invalidIndex < 0) action.transactionally.asTry
                else {
                  val errStr = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                  else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                  DBIO.failed(new Throwable(errStr)).asTry
                }
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }).flatMap({
              case Success(n) =>
                // Add the resource to the resourcechanges table
                logger.debug("PATCH /orgs/" + orgid + "/business/policies/" + policy + " result: " + n)
                val numUpdated = n.asInstanceOf[Int] // i think n is an AnyRef so we have to do this to get it to an int
                if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                  val policyChange = ResourceChangeRow(0L, orgid, policy, "policy", "false", "policy", ResourceChangeConfig.MODIFIED, ApiTime.nowUTCTimestamp)
                  policyChange.insert.asTry
                } else {
                  DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", compositeId))).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PATCH /orgs/" + orgid + "/business/policies/" + policy + " updated in changes table: " + v)
                (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.attribute.updated", attrName, compositeId)))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.updated", compositeId, t.getMessage))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.updated", compositeId, t.getMessage)))
            })
          }
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/business/policies/{policy} ===============================
  @DELETE
  @Path("{policy}")
  @Operation(summary = "Deletes a business policy", description = "Deletes a business policy. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "policy")
  def busPolDeleteRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & delete) { (orgid, policy) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/business/policies/$policy")
    val compositeId = OrgAndId(orgid,policy).toString
    exchAuth(TBusiness(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(BusinessPoliciesTQ.getBusinessPolicy(compositeId).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/business/policies/" + policy + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              AuthCache.removeBusinessOwner(compositeId)
              AuthCache.removeBusinessIsPublic(compositeId)
              val policyChange = ResourceChangeRow(0L, orgid, policy, "policy", "false", "policy", ResourceChangeConfig.DELETED, ApiTime.nowUTCTimestamp)
              policyChange.insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + orgid + "/business/policies/" + policy + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("business.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("business.policy.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("business.policy.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // ======== POST /org/{orgid}/business/policies/{policy}/search ========================
  @POST
  @Path("{policy}/search")
  @Operation(
    summary = "Returns matching nodes for this business policy",
    description = "Returns the matching nodes for this business policy that do not already have an agreement for the specified service. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "policy",
        in = ParameterIn.PATH,
        description = "Pattern name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "changedSince": 123456L,                // [Long > 0L, 0L], Only return nodes that have changed since this Unix epoch time. Value 0L disables filter. Must be > 0L if ignoreOffset is true.
  "nodeOrgids": ["org1", "org2", "..."],  // (optional), Defaults to the same organization the business policy is in
  "numEntries": 100,                      // (optional) [Int > 0], Maximum number of nodes returned
  "session": 1L                           // Constrains multiple Agbot instances to a singular search.
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostBusinessPolicySearchRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[PostBusinessPolicySearchResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad request"
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      ),
      new responses.ApiResponse(
        responseCode = "409",
        description = "old session"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "policy")
  def busPolPostSearchRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment / "search") & post & entity(as[PostBusinessPolicySearchRequest])) { (orgid, policy, reqBody) =>
    val compositeId: String = OrgAndId(orgid, policy).toString
    exchAuth(TNode(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validateWithMsg(if(!((!(reqBody.changedSince < 0L)) && reqBody.session.isValidLong && (reqBody.numEntries.isEmpty || !(reqBody.numEntries.getOrElse(-1) < 0)))) Some(ExchMsg.translate("bad.input")) else None) {
        complete({
          val nodeOrgids: Set[String] = reqBody.nodeOrgids.getOrElse(List(orgid)).toSet
          var searchSvcUrl = ""    // a composite value (org/url), will be set later in the db.run()
          db.run(BusinessPoliciesTQ.getService(compositeId).result.flatMap({ list =>
            if (list.nonEmpty) {
              // Finding the service was successful, form the query for the nodes for the next step
              val service: BService = BusinessPoliciesTQ.getServiceFromString(list.head)    // we should have found only 1 business pol service string, now parse it to get service list
              val optArch: Option[String] =
                if(service.arch.equals("") || service.arch.equals("*"))
                  None
                else
                  Some(service.arch)
              
              searchSvcUrl = OrgAndId(service.org, service.name).toString
              
              /*
                Narrow down the db query results as much as possible by joining the Nodes and NodeAgreements tables and filtering.
                In english, the join gets: n.id, n.nodeType, n.publicKey, a.serviceUrl, a.state
                The filters are: n is in the given list of node orgs, n.pattern is not set, the node is not stale, the node arch matches the service arch (the filter a.state=="" is applied later in our code below)
                After this we have to go thru all of the results and find nodes that do NOT have an agreement for searchSvcUrl.
                Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which is why the agreement cols are Option() )
              */
              val pagination =
                for {
                  currentOffsetSession <- SearchOffsetPolicyTQ.getOffsetSession(ident.getIdentity, compositeId).result.headOption
                  
                  currentOffset: Option[String] =
                    if (currentOffsetSession.isDefined)
                      currentOffsetSession.get._1
                    else
                      None
                  
                  currentSession: Long = currentOffsetSession.getOrElse((None, -1L))._2
                  
                  offset: Option[String] =
                    if ((currentOffset.isEmpty || (currentOffsetSession.isDefined && !(reqBody.session < currentSession))) && 0L < reqBody.changedSince)
                      Some(ApiTime.thenUTC(reqBody.changedSince))
                    else if (currentOffset.isDefined && reqBody.session.equals(currentSession))
                      currentOffset
                    else
                      None
                  
                  desynchronization: Option[Boolean] =
                    if (currentOffsetSession.isDefined && reqBody.session < currentSession)
                      Some(true)
                    else
                      None
                  
                  nodes = NodesTQ.rows
                                 .filterOpt(optArch)((node, arch) ⇒ node.arch === arch)
                                 .filter(_.lastHeartbeat.isDefined)
                                 .filterOpt(offset)((node, changedSince) ⇒ !(node.lastUpdated < changedSince))
                                 .filter(_.orgid inSet nodeOrgids)
                                 .filter(_.pattern === "")
                                 .filter(_.publicKey =!= "")
                                 .filterOpt(desynchronization)((node, _) ⇒ node.id === "")
                                 .map(node ⇒ (node.id, node.lastUpdated, node.nodeType, node.publicKey))
                                 .joinLeft(NodeAgreementsTQ.rows
                                                           .filter(_.agrSvcUrl === searchSvcUrl)
                                                           .map(agreement => (agreement.agrSvcUrl, agreement.nodeId, agreement.state)))
                                 .on((node, agreement) ⇒ node._1 === agreement._2)
                                 .filter ({
                                   case (_, agreement) ⇒
                                     agreement.map(_._2).isEmpty ||
                                     agreement.map(_._1).getOrElse("") === "" ||
                                     agreement.map(_._3).getOrElse("") === ""
                                 })
                                 .sortBy(r ⇒ (r._1._2.asc, r._1._1.asc, r._2.getOrElse(("", "", ""))._1.asc.nullsFirst))
                                 .map(r ⇒ (r._1._1, r._1._2, r._1._3, r._1._4))
                  
                  nodesWoAgreements ← {
                    if (reqBody.numEntries.isDefined)
                      nodes.take(reqBody.numEntries.get)
                    else
                      nodes}.result.map(List[(String, String, String, String)])
                  
                  updateOffset: Option[String] =
                    if (nodesWoAgreements.nonEmpty &&
                        (currentOffsetSession.isEmpty || !(nodesWoAgreements.size < reqBody.numEntries.getOrElse(1))))
                      Some(nodesWoAgreements.lastOption.get._2)
                    else if (currentOffset.isDefined && reqBody.session <= currentSession)
                      currentOffset
                    else
                      None
                      
                  
                  isOffsetUpdated: Boolean =
                    if (desynchronization.getOrElse(false) ||
                        (currentOffset.isDefined && updateOffset.get.equals(currentOffset.get)) ||
                        (currentOffset.isEmpty && updateOffset.isEmpty))
                      false
                    else
                      true
                  
                  updateSession: Long =
                    if(currentOffsetSession.isEmpty || (currentOffsetSession.isDefined && currentSession < reqBody.session))
                      reqBody.session
                    else
                      currentSession
                  
                  _ ← SearchOffsetPolicyTQ.setOffsetSession(ident.identityString, updateOffset, compositeId, updateSession)
                } yield (desynchronization, nodesWoAgreements, isOffsetUpdated)
              
              pagination.transactionally.asTry
            }
            else DBIO.failed(new Throwable(ExchMsg.translate("business.policy.not.found", compositeId))).asTry
          })).map({
            case Success(results) =>
              if(results._2.nonEmpty) {
                (HttpCode.POST_OK,
                  PostBusinessPolicySearchResponse(
                    results._2.map(
                      node =>
                        BusinessPolicyNodeResponse(
                          node._1,
                          node._3 match {
                            case "" => NodeType.DEVICE.toString
                            case _ => node._3
                          },
                          node._4)),
                    results._3))
              }
              else if (results._1.getOrElse(false))
                (HttpCode.ALREADY_EXISTS2, PostBusinessPolicySearchResponse(List[BusinessPolicyNodeResponse](), results._3))
              else
                (HttpCode.NOT_FOUND, PostBusinessPolicySearchResponse(List[BusinessPolicyNodeResponse](), results._3))
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("invalid.input.message", t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }
}
