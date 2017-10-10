/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.registry.web.api;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.registry.authorization.Authorizer;
import org.apache.nifi.registry.authorization.RequestAction;
import org.apache.nifi.registry.bucket.BucketItem;
import org.apache.nifi.registry.exception.ResourceNotFoundException;
import org.apache.nifi.registry.flow.VersionedFlow;
import org.apache.nifi.registry.flow.VersionedFlowSnapshot;
import org.apache.nifi.registry.flow.VersionedFlowSnapshotMetadata;
import org.apache.nifi.registry.service.AuthorizationService;
import org.apache.nifi.registry.service.RegistryService;
import org.apache.nifi.registry.service.params.QueryParameters;
import org.apache.nifi.registry.service.params.SortParameter;
import org.apache.nifi.registry.web.link.LinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.SortedSet;

@Component
@Path("/buckets/{bucketId}/flows")
@Api(
        value = "bucket >> flows",
        description = "Create flows scoped to an existing bucket in the registry."
)
public class BucketFlowResource extends AuthorizableApplicationResource {

    private static final Logger logger = LoggerFactory.getLogger(BucketFlowResource.class);

    private final RegistryService registryService;
    private final LinkService linkService;

    @Autowired
    public BucketFlowResource(
            final RegistryService registryService,
            final LinkService linkService,
            final AuthorizationService authorizationService,
            final Authorizer authorizer) {
        super(authorizer, authorizationService);
        this.registryService = registryService;
        this.linkService = linkService;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Create a named flow and store it in the specified bucket. " +
                    "The flow id is created by the server and a location URI for the created flow resource is returned.",
            response = VersionedFlow.class
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response createFlow(@PathParam("bucketId") final String bucketId, final VersionedFlow flow) {
        authorizeBucketAccess(RequestAction.WRITE, bucketId);
        verifyPathParamsMatchBody(bucketId, flow);
        final VersionedFlow createdFlow = registryService.createFlow(bucketId, flow);
        return Response.status(Response.Status.OK).entity(createdFlow).build();
    }

    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get metadata for all flows in all buckets that the registry has stored for which the client is authorized. The information about " +
                    "the versions of each flow should be obtained by requesting a specific flow by id.",
            response = VersionedFlow.class,
            responseContainer = "List"
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response getFlows(
            @PathParam("bucketId") final String bucketId,
            @ApiParam(value = SortParameter.API_PARAM_DESCRIPTION, format = "field:order", allowMultiple = true, example = "name:ASC")
            @QueryParam("sort")
            final List<String> sortParameters) {
        authorizeBucketAccess(RequestAction.READ, bucketId);

        final QueryParameters.Builder paramsBuilder = new QueryParameters.Builder();
        for (String sortParam : sortParameters) {
            paramsBuilder.addSort(SortParameter.fromString(sortParam));
        }

        final List<VersionedFlow> flows = registryService.getFlows(bucketId);
        linkService.populateFlowLinks(flows);

        return Response.status(Response.Status.OK).entity(flows).build();
    }

    @GET
    @Path("{flowId}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get metadata for an existing flow the registry has stored. If verbose is true, then the metadata " +
                    "about all snapshots for the flow will also be returned.",
            response = VersionedFlow.class
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response getFlow(
            @PathParam("bucketId") final String bucketId,
            @PathParam("flowId") final String flowId,
            @QueryParam("verbose") @DefaultValue("false") boolean verbose) {
        authorizeBucketAccess(RequestAction.READ, bucketId);

        final VersionedFlow flow = registryService.getFlow(bucketId, flowId, verbose);

        linkService.populateFlowLinks(flow);

        if (flow.getSnapshotMetadata() != null) {
            linkService.populateSnapshotLinks(flow.getSnapshotMetadata());
        }

        return Response.status(Response.Status.OK).entity(flow).build();
    }

    @PUT
    @Path("{flowId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Update an existing flow the registry has stored.",
            response = VersionedFlow.class
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response updateFlow(
            @PathParam("bucketId") final String bucketId,
            @PathParam("flowId") final String flowId,
            final VersionedFlow flow) {

        verifyPathParamsMatchBody(bucketId, flowId, flow);
        setBucketItemMetadataIfMissing(bucketId, flowId, flow);

        authorizeBucketAccess(RequestAction.WRITE, bucketId);

        final VersionedFlow updatedFlow = registryService.updateFlow(flow);
        return Response.status(Response.Status.OK).entity(updatedFlow).build();
    }

    @DELETE
    @Path("{flowId}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Delete an existing flow the registry has stored.",
            response = VersionedFlow.class
    )
    @ApiResponses({
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response deleteFlow(
            @PathParam("bucketId") final String bucketId,
            @PathParam("flowId") final String flowId) {

        authorizeBucketAccess(RequestAction.WRITE, bucketId);
        final VersionedFlow deletedFlow = registryService.deleteFlow(bucketId, flowId);
        return Response.status(Response.Status.OK).entity(deletedFlow).build();
    }

    @POST
    @Path("{flowId}/versions")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Create the next version of a given flow ID. " +
                    "The version number is created by the server and a location URI for the created version resource is returned.",
            response = VersionedFlowSnapshot.class
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response createFlowVersion(
            @PathParam("bucketId") final String bucketId,
            @PathParam("flowId") final String flowId,
            final VersionedFlowSnapshot snapshot) {
        verifyPathParamsMatchBody(bucketId, flowId, snapshot);
        authorizeBucketAccess(RequestAction.WRITE, bucketId);

        setSnaphotMetadataIfMissing(bucketId, flowId, snapshot);
        final VersionedFlowSnapshot createdSnapshot = registryService.createFlowSnapshot(snapshot);
        return Response.status(Response.Status.OK).entity(createdSnapshot).build();
    }

    @GET
    @Path("{flowId}/versions")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get summary of all versions of a flow for a given flow ID.",
            response = VersionedFlowSnapshotMetadata.class,
            responseContainer = "List"
    )
    @ApiResponses({
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response getFlowVersions(
            @PathParam("bucketId") final String bucketId,
            @PathParam("flowId") final String flowId) {
        authorizeBucketAccess(RequestAction.READ, bucketId);
        final VersionedFlow flow = registryService.getFlow(bucketId, flowId, true);

        if (flow.getSnapshotMetadata() != null) {
            linkService.populateSnapshotLinks(flow.getSnapshotMetadata());
        }

        return Response.status(Response.Status.OK).entity(flow.getSnapshotMetadata()).build();
    }

    @GET
    @Path("{flowId}/versions/latest")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get the latest version of a flow for a given flow ID",
            response = VersionedFlowSnapshot.class
    )
    @ApiResponses({
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response getLatestFlowVersion(
            @PathParam("bucketId") final String bucketId,
            @PathParam("flowId") final String flowId) {
        authorizeBucketAccess(RequestAction.READ, bucketId);
        final VersionedFlow flow = registryService.getFlow(bucketId, flowId, true);

        final SortedSet<VersionedFlowSnapshotMetadata> snapshots = flow.getSnapshotMetadata();
        if (snapshots == null || snapshots.size() == 0) {
            throw new ResourceNotFoundException("Not flow versions found for flow with id " + flowId);
        }

        final VersionedFlowSnapshotMetadata lastSnapshotMetadata = snapshots.last();
        final VersionedFlowSnapshot lastSnapshot = registryService.getFlowSnapshot(bucketId, flowId, lastSnapshotMetadata.getVersion());

        return Response.status(Response.Status.OK).entity(lastSnapshot).build();
    }

    @GET
    @Path("{flowId}/versions/{versionNumber: \\d+}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get a given version of a flow for a given flow ID",
            response = VersionedFlowSnapshot.class
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = HttpStatusMessages.MESSAGE_400),
            @ApiResponse(code = 401, message = HttpStatusMessages.MESSAGE_401),
            @ApiResponse(code = 403, message = HttpStatusMessages.MESSAGE_403),
            @ApiResponse(code = 404, message = HttpStatusMessages.MESSAGE_404),
            @ApiResponse(code = 409, message = HttpStatusMessages.MESSAGE_409) })
    public Response getFlowVersion(
            @PathParam("bucketId") final String bucketId,
            @PathParam("flowId") final String flowId,
            @PathParam("versionNumber") final Integer versionNumber) {
        authorizeBucketAccess(RequestAction.READ, bucketId);
        final VersionedFlowSnapshot snapshot = registryService.getFlowSnapshot(bucketId, flowId, versionNumber);
        return Response.status(Response.Status.OK).entity(snapshot).build();
    }

    private static void verifyPathParamsMatchBody(String bucketIdParam, BucketItem bodyBucketItem) throws BadRequestException {
        if (StringUtils.isBlank(bucketIdParam)) {
            throw new BadRequestException("Bucket id path parameter cannot be blank");
        }

        if (bodyBucketItem == null) {
            throw new BadRequestException("Object in body cannot be null");
        }

        if (bodyBucketItem.getBucketIdentifier() != null && !bucketIdParam.equals(bodyBucketItem.getBucketIdentifier())) {
            throw new BadRequestException("Bucket id in path param must match bucket id in body");
        }
    }

    private static void verifyPathParamsMatchBody(String bucketIdParam, String flowIdParam, BucketItem bodyBucketItem) throws BadRequestException {
        verifyPathParamsMatchBody(bucketIdParam, bodyBucketItem);

        if (StringUtils.isBlank(flowIdParam)) {
            throw new BadRequestException("Flow id path parameter cannot be blank");
        }

        if (bodyBucketItem.getIdentifier() != null && !flowIdParam.equals(bodyBucketItem.getIdentifier())) {
            throw new BadRequestException("Item id in path param must match item id in body");
        }
    }

    private static void verifyPathParamsMatchBody(String bucketIdParam, String flowIdParam, VersionedFlowSnapshot flowSnapshot) throws BadRequestException {
        if (StringUtils.isBlank(bucketIdParam)) {
            throw new BadRequestException("Bucket id path parameter cannot be blank");
        }

        if (StringUtils.isBlank(flowIdParam)) {
            throw new BadRequestException("Flow id path parameter cannot be blank");
        }

        if (flowSnapshot == null) {
            throw new BadRequestException("VersionedFlowSnapshot cannot be null in body");
        }

        final VersionedFlowSnapshotMetadata metadata = flowSnapshot.getSnapshotMetadata();
        if (metadata != null && metadata.getBucketIdentifier() != null && !bucketIdParam.equals(metadata.getBucketIdentifier())) {
            throw new BadRequestException("Bucket id in path param must match bucket id in body");
        }
        if (metadata != null && metadata.getFlowIdentifier() != null && !flowIdParam.equals(metadata.getFlowIdentifier())) {
            throw new BadRequestException("Flow id in path param must match flow id in body");
        }
    }

    private static void setBucketItemMetadataIfMissing(
            @NotNull String bucketIdParam,
            @NotNull String bucketItemIdParam,
            @NotNull BucketItem bucketItem) {
        if (bucketItem.getBucketIdentifier() == null) {
            bucketItem.setBucketIdentifier(bucketIdParam);
        }

        if (bucketItem.getIdentifier() == null) {
            bucketItem.setIdentifier(bucketItemIdParam);
        }
    }

    private static void setSnaphotMetadataIfMissing(
            @NotNull  String bucketIdParam,
            @NotNull String flowIdParam,
            @NotNull VersionedFlowSnapshot flowSnapshot) {

        VersionedFlowSnapshotMetadata metadata = flowSnapshot.getSnapshotMetadata();
        if (metadata == null) {
            metadata = new VersionedFlowSnapshotMetadata();
        }

        if (metadata.getBucketIdentifier() == null) {
            metadata.setBucketIdentifier(bucketIdParam);
        }

        if (metadata.getFlowIdentifier() == null) {
            metadata.setFlowIdentifier(flowIdParam);
        }

        flowSnapshot.setSnapshotMetadata(metadata);
    }
}