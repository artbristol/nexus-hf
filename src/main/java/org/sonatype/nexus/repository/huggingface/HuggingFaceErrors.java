/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Hub errors are described by {@code X-Error-Code} (e.g. {@code EntryNotFound}, {@code RevisionNotFound},
 * {@code GatedRepo}) and {@code X-Error-Message} headers, which {@code huggingface_hub} turns into specific exceptions.
 *
 * The negative cache only keeps the status of a response, so both are carried in the status message as
 * {@code [code] message} and turned back into headers on the way out.
 */
public final class HuggingFaceErrors
{
  public static final String X_ERROR_CODE = "X-Error-Code";

  public static final String X_ERROR_MESSAGE = "X-Error-Message";

  private static final Pattern ENCODED = Pattern.compile("^\\[([A-Za-z]*)] (.*)$", Pattern.DOTALL);

  private HuggingFaceErrors() {
  }

  public record HubError(@Nullable String code, @Nullable String message)
  {
  }

  public static String encode(@Nullable final String code, @Nullable final String message) {
    return "[" + (code == null ? "" : code) + "] " + (message == null ? "" : message);
  }

  public static Optional<HubError> decode(@Nullable final String statusMessage) {
    if (statusMessage == null) {
      return Optional.empty();
    }
    Matcher matcher = ENCODED.matcher(statusMessage);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String code = matcher.group(1);
    String message = matcher.group(2);
    return Optional.of(new HubError(code.isEmpty() ? null : code, message.isEmpty() ? null : message));
  }
}
