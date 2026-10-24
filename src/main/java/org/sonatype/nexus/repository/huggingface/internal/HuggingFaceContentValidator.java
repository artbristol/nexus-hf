/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.mime.MimeSupport;
import org.sonatype.nexus.repository.mime.ContentValidator;
import org.sonatype.nexus.repository.mime.DefaultContentValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.repository.view.ContentTypes.APPLICATION_JSON;
import static org.sonatype.nexus.repository.view.ContentTypes.APPLICATION_OCTET_STREAM;

/**
 * Hugging Face content validator.
 *
 * Model weights ({@code .safetensors}, {@code .bin}, {@code .gguf}, {@code .pt}, ...) are opaque binaries whose type
 * cannot be detected from their content, which strict content type validation would reject. For such content there is
 * nothing to validate against, so it is accepted; any content whose type can be detected is still checked against its
 * file name, which catches e.g. an HTML error page served as {@code config.json}.
 *
 * Hub API responses have extension-less paths (e.g. {@code /api/models/owner/name/revision/main}); they are validated
 * and typed as the JSON they are declared to be, rather than as the plain text detection would make of them.
 */
@Component
@Qualifier(HuggingFaceFormat.NAME)
public class HuggingFaceContentValidator
    implements ContentValidator
{
  private static final Logger log = LoggerFactory.getLogger(HuggingFaceContentValidator.class);

  private final DefaultContentValidator defaultContentValidator;

  private final MimeSupport mimeSupport;

  @Autowired
  public HuggingFaceContentValidator(
      final DefaultContentValidator defaultContentValidator,
      final MimeSupport mimeSupport)
  {
    this.defaultContentValidator = requireNonNull(defaultContentValidator);
    this.mimeSupport = requireNonNull(mimeSupport);
  }

  @Nonnull
  @Override
  public String determineContentType(
      final boolean strictContentTypeValidation,
      final InputStreamSupplier contentSupplier,
      @Nullable final MimeRulesSource mimeRulesSource,
      @Nullable final String contentName,
      @Nullable final String declaredContentType) throws IOException
  {
    String name = contentName;
    if (name != null && APPLICATION_JSON.equals(declaredContentType) && !hasExtension(name)) {
      // the query string of API paths would hide the extension from type detection
      int query = name.indexOf('?');
      name = (query < 0 ? name : name.substring(0, query)) + ".json";
    }
    boolean strict = strictContentTypeValidation && !isOpaque(contentSupplier, name);
    return defaultContentValidator.determineContentType(
        strict, contentSupplier, mimeRulesSource, name, declaredContentType);
  }

  private static boolean hasExtension(final String path) {
    String baseName = path.substring(path.lastIndexOf('/') + 1);
    int query = baseName.indexOf('?');
    return (query < 0 ? baseName : baseName.substring(0, query)).contains(".");
  }

  private boolean isOpaque(final InputStreamSupplier contentSupplier, @Nullable final String contentName)
      throws IOException
  {
    List<String> detected;
    try (InputStream in = contentSupplier.get()) {
      detected = mimeSupport.detectMimeTypes(in, contentName);
    }
    boolean opaque = detected.isEmpty() || (detected.size() == 1 && detected.contains(APPLICATION_OCTET_STREAM));
    if (opaque) {
      log.debug("Content type of {} cannot be detected; accepting it as opaque binary", contentName);
    }
    return opaque;
  }
}
