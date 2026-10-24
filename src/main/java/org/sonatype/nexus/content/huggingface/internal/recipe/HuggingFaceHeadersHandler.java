/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import javax.annotation.Nonnull;

import org.sonatype.nexus.repository.ETagHeaderUtils;
import org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import org.springframework.stereotype.Component;

import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_LINKED_ETAG;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_LINKED_SIZE;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_REPO_COMMIT;

/**
 * Adds the Hub headers that {@code huggingface_hub} requires on file responses ({@code X-Repo-Commit} is mandatory).
 * The plain {@code ETag} is set from the same metadata by the {@code ContentHeadersHandler}.
 */
@Component
public class HuggingFaceHeadersHandler
    implements Handler
{
  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    Response response = context.proceed();
    if (response.getStatus().isSuccessful() && response.getPayload() instanceof Content content) {
      HuggingFaceFileInfo info = content.getAttributes().get(HuggingFaceFileInfo.class);
      if (info != null) {
        response.getHeaders().replace(X_REPO_COMMIT, info.commit());
        if (info.etag() != null) {
          response.getHeaders().replace(X_LINKED_ETAG, ETagHeaderUtils.quote(info.etag()));
        }
        if (info.size() != null) {
          response.getHeaders().replace(X_LINKED_SIZE, info.size().toString());
        }
      }
    }
    return response;
  }
}
