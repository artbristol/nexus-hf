/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HuggingFaceProxyFacetTest
{
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void stripsXetHashesAtAnyDepth() throws Exception {
    JsonNode node = objectMapper.readTree("""
        [{"type":"file","path":"model.safetensors","xetHash":"abc","lfs":{"oid":"x","xetHash":"def"}},
         {"type":"directory","path":"onnx"}]""");
    HuggingFaceProxyFacet.stripXetHashes(node);
    assertEquals(objectMapper.readTree("""
        [{"type":"file","path":"model.safetensors","lfs":{"oid":"x"}},
         {"type":"directory","path":"onnx"}]"""), node);
  }

  @Test
  public void normalizesEtags() {
    assertEquals("10c66461e4c109db5a2196bff4bb59be30396ed8",
        HuggingFaceProxyFacet.normalizeEtag("\"10c66461e4c109db5a2196bff4bb59be30396ed8\""));
    assertEquals("1389-8N8", HuggingFaceProxyFacet.normalizeEtag("W/\"1389-8N8\""));
    assertEquals("abc", HuggingFaceProxyFacet.normalizeEtag("abc"));
    assertNull(HuggingFaceProxyFacet.normalizeEtag(null));
  }
}
