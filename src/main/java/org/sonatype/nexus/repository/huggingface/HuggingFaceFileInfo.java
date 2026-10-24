/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import static java.util.Objects.requireNonNull;

/**
 * Hub metadata of a file, as the Hub reports it in response headers of {@code HEAD /{repo}/resolve/{rev}/{file}}.
 *
 * @param commit commit hash the revision resolved to ({@code X-Repo-Commit})
 * @param etag   git blob id, or SHA-256 for LFS files ({@code X-Linked-Etag} or {@code ETag}), unquoted
 * @param size   file size in bytes ({@code X-Linked-Size} or {@code Content-Length}), if known
 * @param repoId canonical repository id, if the Hub redirected to it (renamed repository, other spelling)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HuggingFaceFileInfo(String commit, @Nullable String etag, @Nullable Long size, @Nullable String repoId)
{
  public static final String X_REPO_COMMIT = "X-Repo-Commit";

  public static final String X_LINKED_ETAG = "X-Linked-Etag";

  public static final String X_LINKED_SIZE = "X-Linked-Size";

  public HuggingFaceFileInfo {
    requireNonNull(commit);
  }

  public HuggingFaceFileInfo(final String commit, @Nullable final String etag, @Nullable final Long size) {
    this(commit, etag, size, null);
  }

  /**
   * Whether the etag is the SHA-256 of the content, which is the case for LFS files.
   */
  /**
   * Whether the etag identifies the content: SHA-256 for LFS files, git blob id otherwise (a hash over size and
   * content, so the size must be known).
   */
  @JsonIgnore
  public boolean isContentHash() {
    return isSha256Etag() || (etag != null && size != null && etag.matches("^[0-9a-f]{40}$"));
  }

  @JsonIgnore
  public boolean isSha256Etag() {
    return etag != null && etag.matches("^[0-9a-f]{64}$");
  }
}
