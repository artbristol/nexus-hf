/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import java.util.Optional;

import javax.annotation.Nonnull;

import org.sonatype.nexus.repository.ETagHeaderUtils;
import org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.FileRef;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoRef;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import com.google.common.net.HttpHeaders;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_LINKED_ETAG;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_LINKED_SIZE;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_REPO_COMMIT;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_XET_HASH;

/**
 * Adds the Hub headers that {@code huggingface_hub} requires on file responses ({@code X-Repo-Commit} is mandatory),
 * the XET headers where XET is offered, and the pagination link of API listings. The plain {@code ETag} is set from
 * the same metadata by the {@code ContentHeadersHandler}.
 */
@Component
public class HuggingFaceHeadersHandler
    implements Handler
{
  private static final String HUB_URL = "https://huggingface.co";

  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    Response response = context.proceed();
    if (response.getStatus().isSuccessful() && response.getPayload() instanceof Content content) {
      String nextPage = content.getAttributes().get(HuggingFaceProxyFacet.NEXT_PAGE, String.class);
      if (nextPage != null) {
        response.getHeaders().replace(HttpHeaders.LINK, "<" + context.getRepository().getUrl() + "/" + nextPage + ">; rel=\"next\"");
      }
      HuggingFaceFileInfo info = content.getAttributes().get(HuggingFaceFileInfo.class);
      if (info != null) {
        response.getHeaders().replace(X_REPO_COMMIT, info.commit());
        if (info.etag() != null) {
          response.getHeaders().replace(X_LINKED_ETAG, ETagHeaderUtils.quote(info.etag()));
        }
        if (info.size() != null) {
          response.getHeaders().replace(X_LINKED_SIZE, info.size().toString());
        }
        if (info.xetHash() != null) {
          addXetHeaders(context, response, info);
        }
      }
    }
    return response;
  }

  /**
   * The Hub's XET headers, if XET is offered. The token link is the Hub's, which {@code huggingface_hub} rewrites to
   * its endpoint, credentials included; a link to this repository would lose them.
   */
  private static void addXetHeaders(final Context context, final Response response, final HuggingFaceFileInfo info) {
    if (!context.getRepository().facet(HuggingFaceXetFacet.class).isOffered()) {
      return;
    }
    Optional<FileRef> file = HuggingFacePaths.parseFile(context.getRequest().getPath());
    if (file.isEmpty()) {
      return;
    }
    RepoRef repo = new RepoRef(file.get().type(), info.repoId() != null ? info.repoId() : file.get().repoId(),
        info.commit());
    response.getHeaders().replace(X_XET_HASH, info.xetHash());
    response.getHeaders().replace(HttpHeaders.LINK, "<" + HUB_URL + repo.xetTokenPath() + ">; rel=\"xet-auth\"");
  }
}
