/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.FileRef;
import org.sonatype.nexus.repository.huggingface.internal.HuggingFaceFormat;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.hash.HashCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static com.google.common.base.Preconditions.checkArgument;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA256;

/**
 * A {@link HuggingFaceContentFacet} that persists to a {@link org.sonatype.nexus.repository.content.facet.ContentFacet}.
 *
 * One component per Hub repository revision: namespace = owner, name = repository name, version = commit hash,
 * kind = model/dataset/space. Each file is an asset of that component.
 */
@Component
@Qualifier(HuggingFaceFormat.NAME)
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HuggingFaceContentFacetImpl
    extends ContentFacetSupport
    implements HuggingFaceContentFacet
{
  @Autowired
  public HuggingFaceContentFacetImpl(@Qualifier(HuggingFaceFormat.NAME) final FormatStoreManager formatStoreManager) {
    super(formatStoreManager);
  }

  @Override
  public Optional<Content> get(final String path) throws IOException {
    return assets().path(path).find().map(FluentAsset::download);
  }

  @Override
  public Content putFile(
      final FileRef file,
      final Payload content,
      @Nullable final String expectedSha256) throws IOException
  {
    checkArgument(file.isCommit(), "Files are stored at commit revisions: %s", file);
    try (TempBlob blob = blobs().ingest(content, HASHING)) {
      if (expectedSha256 != null) {
        HashCode actual = blob.getHashes().get(SHA256);
        if (actual == null || !expectedSha256.equals(actual.toString())) {
          throw new InvalidContentException(
              "SHA-256 of " + file.path() + " is " + actual + ", upstream reported " + expectedSha256);
        }
      }
      return saveFile(file, blob).markAsCached(content).download();
    }
  }

  @Override
  public Content putFile(
      final FileRef file,
      final Path path,
      @Nullable final String contentType,
      final CacheInfo cacheInfo) throws IOException
  {
    checkArgument(file.isCommit(), "Files are stored at commit revisions: %s", file);
    try (TempBlob blob = blobs().ingest(path, contentType, HASHING, false)) {
      return saveFile(file, blob).markAsCached(cacheInfo).download();
    }
  }

  private FluentAsset saveFile(final FileRef file, final TempBlob blob) {
    FluentComponent component = components()
        .name(file.name())
        .namespace(file.namespace())
        .version(file.revision())
        .kind(file.type().kind())
        .getOrCreate();
    return assets()
        .path(file.path())
        .kind(FILE_KIND)
        .component(component)
        .blob(blob)
        .save();
  }

  @Override
  public Content putApiResponse(
      final String path,
      final Payload content,
      @Nullable final String nextPage) throws IOException
  {
    try (TempBlob blob = blobs().ingest(content, HASHING)) {
      FluentAsset asset = assets()
          .path(path)
          .kind(API_KIND)
          .blob(blob)
          .save()
          .markAsCached(content);
      asset = nextPage == null
          ? asset.withoutAttribute(HUGGINGFACE_ATTRIBUTES)
          : asset.withAttribute(HUGGINGFACE_ATTRIBUTES, Map.of(NEXT_PAGE_ATTRIBUTE, nextPage));
      return asset.download();
    }
  }

  @Override
  public Content putMetadata(final String path, final String kind, final Payload content) throws IOException {
    try (TempBlob blob = blobs().ingest(content, HASHING)) {
      return assets()
          .path(path)
          .kind(kind)
          .blob(blob)
          .save()
          .markAsCached(content)
          .download();
    }
  }
}
