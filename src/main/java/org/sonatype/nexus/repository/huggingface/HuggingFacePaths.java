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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Request paths of the Hugging Face Hub protocol, as used by {@code huggingface_hub}.
 *
 * <ul>
 * <li>{@code /[datasets/|spaces/|kernels/]{repo}/resolve/{revision}/{file}} - a file at a revision</li>
 * <li>{@code /api/{models|datasets|spaces|kernels}/{repo}/...} - repository metadata</li>
 * <li>{@code /api/{models|datasets|spaces|kernels}?...} - listings and searches</li>
 * <li>{@code /api/{organizations|users}/{name}/overview} - publishers (e.g. checked by the {@code kernels} package)</li>
 * <li>{@code /api/{models|datasets|spaces|kernels}/{repo}/xet-read-token/{revision}} - XET access token</li>
 * </ul>
 * and the XET storage (CAS) endpoints this repository stands in for, under {@code /.xet/}:
 * <ul>
 * <li>{@code /.xet/v{1|2}/reconstructions/{file hash}} - how to reconstruct a file from xorb ranges</li>
 * <li>{@code /.xet/xorbs/{xorb hash}/{first byte}-{last byte}} - a byte range of a xorb</li>
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
   * The repository id is {@code owner/name}, or a legacy {@code name} when that does not fit: so
   * {@code /owner/resolve/resolve/main/x} is repository {@code owner/resolve}, not {@code owner} at revision
   * {@code resolve}.
   */
  public static final String FILE_REGEX =
      "^/(?:(datasets|spaces|kernels)/)?([^/]+(?:/[^/]+)?)/resolve/(" + SLASHED_REVISION + "|[^/]+)/(.+)$";

  private static final Pattern SLASHED_REVISION_PATTERN =
      Pattern.compile("(/(?:resolve|revision|tree|paths-info|commits|xet-read-token)/)refs/(pr|convert)/");

  /**
   * The read-only Hub API that clients need to find and download repositories. Everything else stays unproxied, in
   * particular anything about the identity behind the repository's token (whoami, settings, billing, jobs, ...), and
   * XET token endpoints, which this repository answers itself (see {@link #XET_TOKEN_REGEX}).
   */
  public static final String API_REGEX = "^/api/(?:"
      + "(?:models|datasets|spaces|kernels)(?:/(?!.*/xet-[a-z]+-token(?:/|$)).+)?"
      + "|(?:organizations|users)/[^/]+/overview"
      + ")$";

  /**
   * The XET read token of a Hub repository at a revision. (Write tokens stay unproxied.)
   */
  public static final String XET_TOKEN_REGEX = "^/api/(models|datasets|spaces|kernels)/([^/]+(?:/[^/]+)?)/xet-read-token/("
      + SLASHED_REVISION + "|[^/]+)$";

  /**
   * Prefix of the XET storage endpoints; the XET storage URL handed to clients is the repository URL plus this.
   */
  public static final String XET_PREFIX = "/.xet";

  public static final String XET_RECONSTRUCTION_REGEX = "^/\\.xet/v([12])/reconstructions/([0-9a-f]{64})$";

  public static final String XET_XORB_REGEX = "^/\\.xet/xorbs/([0-9a-f]{64})/([0-9]{1,12})-([0-9]{1,12})$";

  private static final Pattern FILE_PATTERN = Pattern.compile(FILE_REGEX);

  private static final Pattern XET_TOKEN_PATTERN = Pattern.compile(XET_TOKEN_REGEX);

  private static final Pattern XET_RECONSTRUCTION_PATTERN = Pattern.compile(XET_RECONSTRUCTION_REGEX);

  private static final Pattern XET_XORB_PATTERN = Pattern.compile(XET_XORB_REGEX);

  private static final Pattern COMMIT_PATTERN = Pattern.compile("^[0-9a-f]{40}$");

  private static final Pattern IMMUTABLE_API_PATTERN =
      Pattern.compile("^/api/(?:models|datasets|spaces|kernels)/.+/(?:revision|tree)/[0-9a-f]{40}(?:/.*)?$");

  private HuggingFacePaths() {
  }

  public enum RepoType
  {
    MODEL("model", ""),
    DATASET("dataset", "datasets/"),
    SPACE("space", "spaces/"),
    KERNEL("kernel", "kernels/");

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

    /**
     * Segment of API paths, e.g. {@code models}.
     */
    public String apiSegment() {
      return kind + "s";
    }

    static RepoType fromApiSegment(final String segment) {
      return switch (segment) {
        case "models" -> MODEL;
        case "datasets" -> DATASET;
        case "spaces" -> SPACE;
        default -> KERNEL;
      };
    }

    static RepoType fromUrlSegment(final String segment) {
      if (segment == null) {
        return MODEL;
      }
      return switch (segment) {
        case "datasets" -> DATASET;
        case "spaces" -> SPACE;
        default -> KERNEL;
      };
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
     * The same file in a repository of another name.
     */
    public FileRef withRepoId(final String otherRepoId) {
      return new FileRef(type, otherRepoId, revision, file);
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

  /**
   * A Hub repository at a revision, as named by an XET token request.
   */
  public record RepoRef(RepoType type, String repoId, String revision)
  {
    public RepoRef {
      requireNonNull(type);
      requireNonNull(repoId);
      requireNonNull(revision);
    }

    /**
     * Path of the XET read token endpoint, e.g. {@code /api/models/owner/name/xet-read-token/main}.
     */
    public String xetTokenPath() {
      return "/api/" + type.apiSegment() + "/" + repoId + "/xet-read-token/" + revision;
    }
  }

  public static Optional<RepoRef> parseXetToken(final String path) {
    Matcher matcher = XET_TOKEN_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(new RepoRef(RepoType.fromApiSegment(matcher.group(1)), matcher.group(2), matcher.group(3)));
  }

  /**
   * A reconstruction request: API version and file hash.
   */
  public record Reconstruction(int version, String fileHash)
  {
  }

  public static Optional<Reconstruction> parseXetReconstruction(final String path) {
    Matcher matcher = XET_RECONSTRUCTION_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(new Reconstruction(Integer.parseInt(matcher.group(1)), matcher.group(2)));
  }

  /**
   * A byte range of a xorb, both ends inclusive, as in a {@code Range} header.
   */
  public record XorbRange(String xorbHash, long first, long last)
  {
    public XorbRange {
      requireNonNull(xorbHash);
      checkArgument(0 <= first && first <= last, "Invalid xorb range %s-%s", first, last);
    }

    public long length() {
      return last - first + 1;
    }

    public String path() {
      return XET_PREFIX + "/xorbs/" + xorbHash + "/" + first + "-" + last;
    }
  }

  public static Optional<XorbRange> parseXetXorb(final String path) {
    Matcher matcher = XET_XORB_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    long first = Long.parseLong(matcher.group(2));
    long last = Long.parseLong(matcher.group(3));
    return first <= last ? Optional.of(new XorbRange(matcher.group(1), first, last)) : Optional.empty();
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
