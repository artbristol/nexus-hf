/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.FileRef;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;

import com.google.common.collect.ImmutableList;

import static org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA256;

/**
 * Provides persistent content for the 'huggingface' format.
 */
@Facet.Exposed
public interface HuggingFaceContentFacet
    extends ContentFacet
{
  Iterable<HashAlgorithm> HASHING = ImmutableList.of(MD5, SHA1, SHA256);

  /**
   * Asset kind of repository files.
   */
  String FILE_KIND = "file";

  /**
   * Asset kind of cached file metadata, see {@link org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo}.
   */
  String FILE_METADATA_KIND = "file-metadata";

  /**
   * Asset kind of cached Hub API responses.
   */
  String API_KIND = "api";

  /**
   * Asset kind of cached XET storage data: xorb ranges and file reconstructions.
   */
  String XET_KIND = "xet";

  String HUGGINGFACE_ATTRIBUTES = "huggingface";

  String NEXT_PAGE_ATTRIBUTE = "nextPage";

  Optional<Content> get(String path) throws IOException;

  /**
   * Stores a repository file as an asset of the component for its repository and commit, after checking it against
   * the Hub's content hash ({@link HuggingFaceFileInfo#isContentHash()}), when there is one.
   *
   * @param file the file, at a commit hash revision
   * @throws org.sonatype.nexus.repository.InvalidContentException if the content does not match
   */
  Content putFile(FileRef file, Payload content, @Nullable HuggingFaceFileInfo info) throws IOException;

  /**
   * Stores a repository file from a local file, which may be hard-linked into the blob store.
   *
   * @param file the file, at a commit hash revision
   */
  Content putFile(FileRef file, Path path, @Nullable String contentType, CacheInfo cacheInfo) throws IOException;

  /**
   * Stores XET storage data from a local file, as a component-less asset.
   */
  Content putXetData(String path, Path file, String contentType) throws IOException;

  /**
   * Stores a component-less asset.
   */
  Content putMetadata(String path, String kind, Payload content) throws IOException;

  /**
   * Stores a Hub API response, with the link to its next page if it is one page of a listing.
   *
   * @param nextPage path and query of the next page, relative to the repository
   * @see #nextPage(Content)
   */
  Content putApiResponse(String path, Payload content, @Nullable String nextPage) throws IOException;

  /**
   * The next-page link of a stored API response.
   */
  @Nullable
  static String nextPage(final Content content) {
    Asset asset = content.getAttributes().get(Asset.class);
    return asset == null ? null : asset.attributes(HUGGINGFACE_ATTRIBUTES).get(NEXT_PAGE_ATTRIBUTE, String.class);
  }
}
