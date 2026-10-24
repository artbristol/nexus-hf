/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.sonatype.nexus.mime.internal.DefaultMimeSupport;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.mime.DefaultContentValidator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Runs against Nexus' real MIME detection, which decides what strict content type validation accepts.
 */
public class HuggingFaceContentValidatorTest
{
  private static final boolean STRICT = true;

  private final DefaultMimeSupport mimeSupport = new DefaultMimeSupport();

  private final HuggingFaceContentValidator underTest =
      new HuggingFaceContentValidator(new DefaultContentValidator(mimeSupport), mimeSupport);

  @Test
  public void acceptsModelWeightsOfUndetectableType() throws IOException {
    // what a safetensors file starts with: a little-endian header length and a JSON header, then raw tensors
    byte[] safetensors = concat(new byte[]{24, 0, 0, 0, 0, 0, 0, 0},
        "{\"w\":{\"dtype\":\"F32\"}}   ".getBytes(StandardCharsets.UTF_8), random(4096));
    for (String name : new String[]{"model.safetensors", "model-00001-of-00002.safetensors", "ggml-model-q4_0.gguf",
        "weights.pt", "consolidated.00.pth", "tokenizer.model", "spiece.model"}) {
      assertEquals(name, "application/octet-stream", determine("/owner/name/resolve/abc/" + name, safetensors, null));
    }
  }

  /**
   * The point of strict validation for a proxy: an upstream error page must not be cached as an API response.
   */
  @Test
  public void rejectsHtmlServedAsApiResponse() {
    byte[] html = "<!DOCTYPE html><html><head><title>Error</title></head><body>Bad gateway</body></html>"
        .getBytes(StandardCharsets.UTF_8);
    assertThrows(InvalidContentException.class,
        () -> determine("/api/models/owner/name/revision/main", html, "application/json"));
  }

  /**
   * Repository files are whatever their authors uploaded, e.g. hf-internal-testing/fixtures_image_utils has a PNG
   * called ai2d-demo.jpg. They are checked against the Hub's content hashes instead.
   */
  @Test
  public void acceptsMislabelledRepositoryFiles() throws IOException {
    byte[] png = concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'},
        random(64));
    assertEquals("image/jpeg",
        determine("/datasets/owner/name/resolve/abc/ai2d-demo.jpg", png, "image/jpeg"));
  }

  @Test
  public void acceptsRepositoryFilesOfKnownTypes() throws IOException {
    byte[] json = "{\"model_type\": \"gpt2\", \"n_layer\": 2}".getBytes(StandardCharsets.UTF_8);
    assertEquals("application/json", determine("/owner/name/resolve/abc/config.json", json, "application/json"));
    assertEquals("text/plain", determine("/owner/name/resolve/abc/merges.txt", "a b\nc d\n".getBytes(), "text/plain"));
  }

  @Test
  public void typesApiResponsesAsJsonDespiteTheirExtensionlessPaths() throws IOException {
    byte[] listing = "[{\"type\":\"file\",\"path\":\"config.json\",\"size\":665}]".getBytes(StandardCharsets.UTF_8);
    for (String path : new String[]{
        "/api/models/owner/name/revision/main",
        "/api/models/owner/name/tree/main?recursive=true&expand=false",
        "/api/datasets/owner/name/tree/main/data.v2?cursor=abc.def",
        "/api/models/owner/name/revision/v1.0",
        "/api/datasets/owner/name/tree/main/images.png"}) {
      assertEquals(path, "application/json", determine(path, listing, "application/json"));
    }
  }

  private String determine(final String name, final byte[] content, final String declared) throws IOException {
    return underTest.determineContentType(STRICT, () -> new ByteArrayInputStream(content), null, name, declared);
  }

  private static byte[] random(final int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }

  private static byte[] concat(final byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) {
      length += part.length;
    }
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, result, offset, part.length);
      offset += part.length;
    }
    return result;
  }
}
