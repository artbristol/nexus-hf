/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HuggingFaceFileInfoTest
{
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void roundTripsThroughJson() throws Exception {
    HuggingFaceFileInfo info = new HuggingFaceFileInfo("607a30d783dfa663caf39e06633721c8d4cfcd7e",
        "248dfc3911869ec493c76e65bf2fcf7f615828b0254c12b473182f0f81d3a707", 548105171L);
    byte[] json = objectMapper.writeValueAsBytes(info);
    assertEquals(info, objectMapper.readValue(json, HuggingFaceFileInfo.class));
    assertFalse(new String(json).contains("sha256Etag"));
  }

  @Test
  public void readsUnknownSizeAndEtag() throws Exception {
    HuggingFaceFileInfo info = objectMapper.readValue("{\"commit\":\"abc\"}", HuggingFaceFileInfo.class);
    assertEquals(new HuggingFaceFileInfo("abc", null, null), info);
  }

  @Test
  public void recognisesLfsEtags() {
    assertTrue(new HuggingFaceFileInfo("c", "248dfc3911869ec493c76e65bf2fcf7f615828b0254c12b473182f0f81d3a707", 1L)
        .isSha256Etag());
    assertFalse(new HuggingFaceFileInfo("c", "10c66461e4c109db5a2196bff4bb59be30396ed8", 1L).isSha256Etag());
    assertFalse(new HuggingFaceFileInfo("c", null, 1L).isSha256Etag());
  }
}
