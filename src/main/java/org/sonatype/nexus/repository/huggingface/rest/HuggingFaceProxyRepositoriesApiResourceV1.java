/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.rest;

import org.sonatype.nexus.repository.rest.api.AbstractProxyRepositoriesApiResource;
import org.sonatype.nexus.repository.rest.api.FormatAndType;
import org.sonatype.nexus.repository.rest.api.RepositoriesApiResourceV1;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.repository.huggingface.rest.HuggingFaceProxyRepositoriesApiResourceV1.RESOURCE_URI;
import static org.sonatype.nexus.rest.ApiDocConstants.API_REPOSITORY_MANAGEMENT;
import static org.sonatype.nexus.rest.ApiDocConstants.AUTHENTICATION_REQUIRED;
import static org.sonatype.nexus.rest.ApiDocConstants.BAD_REQUEST;
import static org.sonatype.nexus.rest.ApiDocConstants.INSUFFICIENT_PERMISSIONS;
import static org.sonatype.nexus.rest.ApiDocConstants.REPOSITORY_CREATED;
import static org.sonatype.nexus.rest.ApiDocConstants.REPOSITORY_UPDATED;

@Component
@Path(RESOURCE_URI)
@Tag(name = API_REPOSITORY_MANAGEMENT)
public class HuggingFaceProxyRepositoriesApiResourceV1
    extends AbstractProxyRepositoriesApiResource<HuggingFaceProxyRepositoryApiRequest>
{
  static final String RESOURCE_URI = RepositoriesApiResourceV1.RESOURCE_URI + "/huggingface/proxy";

  @Autowired
  public void setConfigurationConverter(
      final HuggingFaceProxyRepositoryApiRequestToConfigurationConverter configurationConverter)
  {
    super.setConfigurationConverter(configurationConverter);
  }

  @Operation(summary = "Create Hugging Face proxy repository")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "201", description = REPOSITORY_CREATED),
      @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
      @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS)
  })
  @POST
  @Override
  public Response createRepository(final HuggingFaceProxyRepositoryApiRequest request) {
    return super.createRepository(request);
  }

  @Operation(summary = "Update Hugging Face proxy repository")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "204", description = REPOSITORY_UPDATED),
      @ApiResponse(responseCode = "400", description = BAD_REQUEST),
      @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
      @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS)
  })
  @PUT
  @Path("/{repositoryName}")
  @Override
  public Response updateRepository(
      final HuggingFaceProxyRepositoryApiRequest request,
      @Parameter(
          description = "Name of the repository to update") @PathParam("repositoryName") final String repositoryName)
  {
    return super.updateRepository(request, repositoryName);
  }

  @GET
  @Path("/{repositoryName}")
  @Override
  @Operation(summary = "Get repository")
  public AbstractApiRepository getRepository(
      @Parameter(hidden = true) @BeanParam final FormatAndType formatAndType,
      @PathParam("repositoryName") final String repositoryName)
  {
    return super.getRepository(formatAndType, repositoryName);
  }
}
