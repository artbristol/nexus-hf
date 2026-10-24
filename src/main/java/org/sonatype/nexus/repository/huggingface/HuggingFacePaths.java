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

import static java.util.Objects.requireNonNull;

/**
 * Request paths of the Hugging Face Hub protocol, as used by {@code huggingface_hub}.
 *
 * <ul>
 * <li>{@code /[datasets/|spaces/]{repo}/resolve/{revision}/{file}} - a file at a revision</li>
 * <li>{@code /api/{models|datasets|spaces}/{repo}/...} - repository metadata</li>
 * </ul>
 * where {@code {repo}} is {@code name} (legacy) or {@code owner/name}.
 */
public final class HuggingFacePaths
{
  /**
   * Revisions that contain slashes. Clients send them encoded ({@code refs%2Fpr%2F1}), but the path seen by the
   * repository is decoded.
   */
  private static final String SLASHED_REVISION = "refs/(?:pr|convert)/[^/]+";

  /**
   * The repository id is matched lazily, so {@code owner/name} is only tried when {@code name/resolve} does not match.
   */
  public static final String FILE_REGEX =
      "^/(?:(datasets|spaces)/)?([^/]+(?:/[^/]+)??)/resolve/(" + SLASHED_REVISION + "|[^/]+)/(.+)$";

  private static final Pattern SLASHED_REVISION_PATTERN =
      Pattern.compile("(/(?:resolve|revision|tree|paths-info|commits)/)refs/(pr|convert)/");

  /**
   * XET token endpoints are excluded: this proxy never offers the XET protocol.
   */
  public static final String API_REGEX = "^/api/(?:models|datasets|spaces)/(?!.*/xet-[a-z]+-token(?:/|$)).+$";

  private static final Pattern FILE_PATTERN = Pattern.compile(FILE_REGEX);

  private static final Pattern COMMIT_PATTERN = Pattern.compile("^[0-9a-f]{40}$");

  private static final Pattern IMMUTABLE_API_PATTERN =
      Pattern.compile("^/api/(?:models|datasets|spaces)/.+/(?:revision|tree)/[0-9a-f]{40}(?:/.*)?$");

  private HuggingFacePaths() {
  }

  public enum RepoType
  {
    MODEL("model", ""),
    DATASET("dataset", "datasets/"),
    SPACE("space", "spaces/");

    private final String kind;

    private final String urlPrefix;

    RepoType(final String kind, final String urlPrefix) {
      this.kind = kind;
      this.urlPrefix = urlPrefix;
    }

    /**
     * Component kind.
     */
    public String kind() {
      return kind;
    }

    /**
     * Prefix of file URLs, e.g. {@code datasets/}.
     */
    public String urlPrefix() {
      return urlPrefix;
    }

    static RepoType fromUrlSegment(final String segment) {
      if (segment == null) {
        return MODEL;
      }
      return "datasets".equals(segment) ? DATASET : SPACE;
    }
  }

  /**
   * A file in a Hub repository at a given revision (branch, tag or commit hash).
   */
  public record FileRef(RepoType type, String repoId, String revision, String file)
  {
    public FileRef {
      requireNonNull(type);
      requireNonNull(repoId);
      requireNonNull(revision);
      requireNonNull(file);
    }

    /**
     * The same file at another revision.
     */
    public FileRef at(final String otherRevision) {
      return new FileRef(type, repoId, otherRevision, file);
    }

    /**
     * Path of the file in Hub URL form, e.g. {@code /owner/name/resolve/main/config.json}.
     */
    public String path() {
      return "/" + type.urlPrefix() + repoId + "/resolve/" + revision + "/" + file;
    }

    public boolean isCommit() {
      return isCommitHash(revision);
    }

    /**
     * Owner of the repository; empty for legacy repositories without one.
     */
    public String namespace() {
      int slash = repoId.indexOf('/');
      return slash < 0 ? "" : repoId.substring(0, slash);
    }

    public String name() {
      return repoId.substring(repoId.indexOf('/') + 1);
    }
  }

  public static Optional<FileRef> parseFile(final String path) {
    Matcher matcher = FILE_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(new FileRef(
        RepoType.fromUrlSegment(matcher.group(1)), matcher.group(2), matcher.group(3), matcher.group(4)));
  }

  public static boolean isApi(final String path) {
    return path.matches(API_REGEX);
  }

  /**
   * Whether an API path addresses a fixed commit, so its response never changes.
   */
  public static boolean isImmutableApi(final String path) {
    return IMMUTABLE_API_PATTERN.matcher(path).matches();
  }

  /**
   * Re-encodes the slashes of a {@code refs/pr/N} or {@code refs/convert/X} revision in an (otherwise escaped) URL path,
   * as the Hub expects them.
   */
  public static String encodeSlashedRevision(final String path) {
    return SLASHED_REVISION_PATTERN.matcher(path).replaceFirst("$1refs%2F$2%2F");
  }

  public static boolean isCommitHash(final String revision) {
    return COMMIT_PATTERN.matcher(revision).matches();
  }
}
