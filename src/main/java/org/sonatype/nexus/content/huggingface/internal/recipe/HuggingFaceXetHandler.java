/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import java.util.Optional;

import javax.annotation.Nonnull;

import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.Reconstruction;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoRef;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.XorbRange;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import org.springframework.stereotype.Component;

/**
 * Serves the XET token endpoint and the XET storage endpoints, see {@link HuggingFaceXetFacet}.
 */
@Component
public class HuggingFaceXetHandler
    implements Handler
{
  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    HuggingFaceXetFacet xet = context.getRepository().facet(HuggingFaceXetFacet.class);
    String path = context.getRequest().getPath();

    Optional<RepoRef> token = HuggingFacePaths.parseXetToken(path);
    if (token.isPresent()) {
      return xet.token(token.get());
    }
    Optional<Reconstruction> reconstruction = HuggingFacePaths.parseXetReconstruction(path);
    if (reconstruction.isPresent()) {
      return xet.reconstruction(context, reconstruction.get());
    }
    Optional<XorbRange> xorb = HuggingFacePaths.parseXetXorb(path);
    if (xorb.isPresent()) {
      return xet.xorb(context, xorb.get());
    }
    return HttpResponses.notFound();
  }
}
