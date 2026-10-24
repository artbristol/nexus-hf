/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface;

import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.FileRef;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HuggingFacePathsTest
{
  private static final String SHA = "607a30d783dfa663caf39e06633721c8d4cfcd7e";

  @Test
  public void parsesNamespacedModelFile() {
    FileRef file = HuggingFacePaths.parseFile("/openai-community/gpt2/resolve/main/config.json").orElseThrow();
    assertEquals(new FileRef(RepoType.MODEL, "openai-community/gpt2", "main", "config.json"), file);
    assertEquals("openai-community", file.namespace());
    assertEquals("gpt2", file.name());
    assertFalse(file.isCommit());
  }

  @Test
  public void parsesLegacyModelFile() {
    FileRef file = HuggingFacePaths.parseFile("/gpt2/resolve/main/onnx/decoder_model.onnx").orElseThrow();
    assertEquals(new FileRef(RepoType.MODEL, "gpt2", "main", "onnx/decoder_model.onnx"), file);
    assertEquals("", file.namespace());
    assertEquals("gpt2", file.name());
  }

  @Test
  public void parsesDatasetAndSpaceFiles() {
    assertEquals(new FileRef(RepoType.DATASET, "stanfordnlp/imdb", SHA, "plain_text/train-00000-of-00001.parquet"),
        HuggingFacePaths.parseFile("/datasets/stanfordnlp/imdb/resolve/" + SHA + "/plain_text/train-00000-of-00001.parquet")
            .orElseThrow());
    assertEquals(new FileRef(RepoType.SPACE, "owner/app", "main", "app.py"),
        HuggingFacePaths.parseFile("/spaces/owner/app/resolve/main/app.py").orElseThrow());
  }

  @Test
  public void fileNamesMayContainResolve() {
    assertEquals(new FileRef(RepoType.MODEL, "owner/name", "main", "resolve/x.bin"),
        HuggingFacePaths.parseFile("/owner/name/resolve/main/resolve/x.bin").orElseThrow());
  }

  @Test
  public void pathRoundTrips() {
    for (String path : new String[]{
        "/openai-community/gpt2/resolve/main/config.json",
        "/gpt2/resolve/main/config.json",
        "/datasets/stanfordnlp/imdb/resolve/main/README.md"}) {
      assertEquals(path, HuggingFacePaths.parseFile(path).orElseThrow().path());
    }
  }

  @Test
  public void atCommit() {
    FileRef file = HuggingFacePaths.parseFile("/openai-community/gpt2/resolve/main/config.json").orElseThrow().at(SHA);
    assertTrue(file.isCommit());
    assertEquals("/openai-community/gpt2/resolve/" + SHA + "/config.json", file.path());
  }

  @Test
  public void prefersOwnerAndNameOverLegacyRepositoryIds() {
    // a repository called "resolve"
    assertEquals(new FileRef(RepoType.MODEL, "owner/resolve", "main", "x.bin"),
        HuggingFacePaths.parseFile("/owner/resolve/resolve/main/x.bin").orElseThrow());
    // legacy repository with a directory called "resolve"
    assertEquals(new FileRef(RepoType.MODEL, "gpt2", "main", "resolve/x.bin"),
        HuggingFacePaths.parseFile("/gpt2/resolve/main/resolve/x.bin").orElseThrow());
  }

  /**
   * The repository sees decoded paths; whatever a file is called, it must come back out unchanged.
   */
  @Test
  public void fileNamesWithAnyCharactersRoundTrip() {
    for (String file : new String[]{
        "examples_Rebecca_(1939_poster)_Small.jpeg", "with space.txt", "plus+sign.txt", "hash#tag.txt",
        "percent%20literal.txt", "question?mark.txt", "ünïcødé/文件.txt", "semi;colon&amp.txt", "[brackets].txt",
        "deep/er/nest/ed/file.bin", ".hidden", "trailing.dot."}) {
      String path = "/datasets/owner/name.with.dots/resolve/main/" + file;
      FileRef ref = HuggingFacePaths.parseFile(path).orElseThrow(() -> new AssertionError(file));
      assertEquals(file, ref.file());
      assertEquals("owner/name.with.dots", ref.repoId());
      assertEquals(path, ref.path());
    }
  }

  @Test
  public void parsesSlashedRevisions() {
    assertEquals(new FileRef(RepoType.MODEL, "openai-community/gpt2", "refs/pr/1", "config.json"),
        HuggingFacePaths.parseFile("/openai-community/gpt2/resolve/refs/pr/1/config.json").orElseThrow());
    assertEquals(new FileRef(RepoType.DATASET, "stanfordnlp/imdb", "refs/convert/parquet", "plain_text/x.parquet"),
        HuggingFacePaths.parseFile("/datasets/stanfordnlp/imdb/resolve/refs/convert/parquet/plain_text/x.parquet")
            .orElseThrow());
    // a plain branch named like a directory is still a one-segment revision
    assertEquals(new FileRef(RepoType.MODEL, "owner/name", "refs", "heads/x"),
        HuggingFacePaths.parseFile("/owner/name/resolve/refs/heads/x").orElseThrow());
  }

  @Test
  public void encodesSlashedRevisions() {
    assertEquals("openai-community/gpt2/resolve/refs%2Fpr%2F1/config.json",
        HuggingFacePaths.encodeSlashedRevision("openai-community/gpt2/resolve/refs/pr/1/config.json"));
    assertEquals("api/models/openai-community/gpt2/revision/refs%2Fpr%2F1",
        HuggingFacePaths.encodeSlashedRevision("api/models/openai-community/gpt2/revision/refs/pr/1"));
    assertEquals("api/datasets/a/b/tree/refs%2Fconvert%2Fparquet/plain_text",
        HuggingFacePaths.encodeSlashedRevision("api/datasets/a/b/tree/refs/convert/parquet/plain_text"));
    assertEquals("owner/name/resolve/main/refs/pr/1",
        HuggingFacePaths.encodeSlashedRevision("owner/name/resolve/main/refs/pr/1"));
  }

  @Test
  public void rejectsNonFilePaths() {
    assertFalse(HuggingFacePaths.parseFile("/openai-community/gpt2").isPresent());
    assertFalse(HuggingFacePaths.parseFile("/openai-community/gpt2/blob/main/config.json").isPresent());
    assertFalse(HuggingFacePaths.parseFile("/a/b/c/resolve/main/x").isPresent());
    assertFalse(HuggingFacePaths.parseFile("/owner/name/resolve/main/").isPresent());
  }

  @Test
  public void kernelFiles() {
    assertEquals(new FileRef(RepoType.KERNEL, "kernels-community/megablocks", "main", "build/x/__init__.py"),
        HuggingFacePaths.parseFile("/kernels/kernels-community/megablocks/resolve/main/build/x/__init__.py")
            .orElseThrow());
  }

  @Test
  public void proxiesTheReadOnlyApiOnly() {
    for (String path : new String[]{
        "/api/models", "/api/datasets", "/api/kernels", "/api/kernels/kernels-community/megablocks",
        "/api/kernels/kernels-community/megablocks/tree/main/build", "/api/organizations/openai/overview",
        "/api/users/julien-c/overview"}) {
      assertTrue(path, HuggingFacePaths.isApi(path));
    }
    for (String path : new String[]{
        "/api/whoami-v2", "/api/settings/repositories", "/api/notifications", "/api/organizations/openai/members",
        "/api/organizations/openai/settings/tokens", "/api/users/julien-c/billing/usage/live", "/api/jobs/openai",
        "/api/kernels/kernels-community/megablocks/xet-read-token/main", "/api/models/a/b/xet-write-token/main",
        "/api/buckets/a/b", "/api/modelsx", "/api/organizations/openai/overview/x"}) {
      assertFalse(path, HuggingFacePaths.isApi(path));
    }
  }

  @Test
  public void apiPaths() {
    assertTrue(HuggingFacePaths.isApi("/api/models/openai-community/gpt2/revision/main"));
    assertTrue(HuggingFacePaths.isApi("/api/datasets/stanfordnlp/imdb/tree/main/plain_text"));
    assertTrue(HuggingFacePaths.isApi("/api/models/gpt2"));
    assertFalse(HuggingFacePaths.isApi("/api/models/openai-community/gpt2/xet-read-token/" + SHA));
    assertFalse(HuggingFacePaths.isApi("/api/whoami-v2"));
  }

  @Test
  public void immutableApiPaths() {
    assertTrue(HuggingFacePaths.isImmutableApi("/api/models/openai-community/gpt2/revision/" + SHA));
    assertTrue(HuggingFacePaths.isImmutableApi("/api/models/openai-community/gpt2/tree/" + SHA + "/onnx"));
    assertFalse(HuggingFacePaths.isImmutableApi("/api/models/openai-community/gpt2/revision/main"));
    assertFalse(HuggingFacePaths.isImmutableApi("/api/models/openai-community/gpt2"));
  }
}
