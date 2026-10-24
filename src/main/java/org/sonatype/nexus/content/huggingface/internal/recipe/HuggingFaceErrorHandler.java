/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import javax.annotation.Nonnull;

import org.sonatype.nexus.repository.huggingface.HuggingFaceErrors;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import org.springframework.stereotype.Component;

import static org.sonatype.nexus.repository.huggingface.HuggingFaceErrors.X_ERROR_CODE;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceErrors.X_ERROR_MESSAGE;

/**
 * Restores Hub error headers from the status message of error responses, including those served from the negative
 * cache. Must be placed before the negative cache handler.
 *
 * @see HuggingFaceErrors
 */
@Component
public class HuggingFaceErrorHandler
    implements Handler
{
  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    Response response = context.proceed();
    if (!response.getStatus().isSuccessful()) {
      HuggingFaceErrors.decode(response.getStatus().getMessage()).ifPresent(error -> {
        if (error.code() != null) {
          response.getHeaders().replace(X_ERROR_CODE, error.code());
        }
        if (error.message() != null) {
          response.getHeaders().replace(X_ERROR_MESSAGE, error.message());
        }
      });
    }
    return response;
  }
}
