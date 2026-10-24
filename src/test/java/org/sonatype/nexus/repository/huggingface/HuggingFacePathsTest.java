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
  public void rejectsNonFilePaths() {
    assertFalse(HuggingFacePaths.parseFile("/openai-community/gpt2").isPresent());
    assertFalse(HuggingFacePaths.parseFile("/openai-community/gpt2/blob/main/config.json").isPresent());
    assertFalse(HuggingFacePaths.parseFile("/a/b/c/resolve/main/x").isPresent());
    assertFalse(HuggingFacePaths.parseFile("/owner/name/resolve/main/").isPresent());
  }

  @Test
  public void apiPaths() {
    assertTrue(HuggingFacePaths.isApi("/api/models/openai-community/gpt2/revision/main"));
    assertTrue(HuggingFacePaths.isApi("/api/datasets/stanfordnlp/imdb/tree/main/plain_text"));
    assertTrue(HuggingFacePaths.isApi("/api/models/gpt2"));
    assertFalse(HuggingFacePaths.isApi("/api/models/openai-community/gpt2/xet-read-token/" + SHA));
    assertFalse(HuggingFacePaths.isApi("/api/whoami-v2"));
    assertFalse(HuggingFacePaths.isApi("/api/models"));
  }

  @Test
  public void immutableApiPaths() {
    assertTrue(HuggingFacePaths.isImmutableApi("/api/models/openai-community/gpt2/revision/" + SHA));
    assertTrue(HuggingFacePaths.isImmutableApi("/api/models/openai-community/gpt2/tree/" + SHA + "/onnx"));
    assertFalse(HuggingFacePaths.isImmutableApi("/api/models/openai-community/gpt2/revision/main"));
    assertFalse(HuggingFacePaths.isImmutableApi("/api/models/openai-community/gpt2"));
  }
}
