/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface;

import java.io.IOException;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
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

  Optional<Content> get(String path) throws IOException;

  /**
   * Stores a repository file as an asset of the component for its repository and commit.
   *
   * @param file           the file, at a commit hash revision
   * @param expectedSha256 SHA-256 the content must have, if known
   */
  Content putFile(FileRef file, Payload content, @Nullable String expectedSha256) throws IOException;

  /**
   * Stores a component-less asset.
   */
  Content putMetadata(String path, String kind, Payload content) throws IOException;
}
