/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.sonatype.nexus.repository.huggingface.internal.xet.XetReconstruction.Part;
import org.sonatype.nexus.repository.huggingface.internal.xet.XetReconstruction.Slice;
import org.sonatype.nexus.repository.huggingface.internal.xet.XetReconstruction.Term;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Against real reconstructions from the Hub's XET storage (presigned URLs replaced), of files of
 * hf-internal-testing/tiny-random-gpt2: pytorch_model.bin (3561811 bytes, 3 terms) in both API versions, and
 * tf_model.h5 (8462256 bytes, 39 terms, repeating chunks).
 */
public class XetReconstructionTest
{
  private static final String XORB = "b8cfb9c1f6d87757ddb987f60b3f039fa0462d47dedb9a789cf89491a155a55f";

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void readsVersion2() throws IOException {
    XetReconstruction reconstruction = load("reconstruction-v2-pytorch_model.bin.json");
    assertEquals(3561811, reconstruction.size());
    assertEquals(List.of(new Term(XORB, 21, 50, 2930033), new Term(XORB, 23, 24, 10543), new Term(XORB, 50, 55, 621235)),
        reconstruction.terms());
    assertEquals(List.of(new Part(21, 55, 904006, 1808937)), reconstruction.xorbs().get(XORB));
    assertEquals(1, reconstruction.fetches().size());
  }

  @Test
  public void readsVersion1Alike() throws IOException {
    XetReconstruction v1 = load("reconstruction-v1-pytorch_model.bin.json");
    XetReconstruction v2 = load("reconstruction-v2-pytorch_model.bin.json");
    assertEquals(v2.size(), v1.size());
    assertEquals(v2.terms(), v1.terms());
    assertEquals(v2.xorbs(), v1.xorbs());
    assertEquals(v2.fetches().get(0).parts(), v1.fetches().get(0).parts());
  }

  @Test
  public void addsUpToTheFileSize() throws IOException {
    XetReconstruction reconstruction = load("reconstruction-v2-tf_model.h5.json");
    assertEquals(8462256, reconstruction.size());
    assertEquals(39, reconstruction.terms().size());
  }

  @Test
  public void theWholeFileIsOneSlice() throws IOException {
    XetReconstruction reconstruction = load("reconstruction-v2-tf_model.h5.json");
    Slice slice = reconstruction.slice(0, Long.MAX_VALUE).orElseThrow();
    assertEquals(0, slice.offsetIntoFirstRange());
    assertEquals(reconstruction.terms(), slice.terms());
    assertEquals(reconstruction.xorbs(), slice.xorbs());
  }

  /**
   * Windows as the client asks for them, read back as the client does ({@code retrieve_file_term_block} of
   * {@code hf_xet}): terms in order from the window start, the first one less its offset, the last one trimmed to the
   * window end. That must give exactly the bytes of the window, at their place in the file.
   */
  @Test
  public void slicesGiveTheBytesOfTheirWindow() throws IOException {
    XetReconstruction reconstruction = load("reconstruction-v2-tf_model.h5.json");
    List<Term> terms = reconstruction.terms();
    long[] starts = new long[terms.size()];
    for (int i = 1; i < terms.size(); i++) {
      starts[i] = starts[i - 1] + terms.get(i - 1).unpackedLength();
    }
    long size = reconstruction.size();
    long[] edges = {0, 1, starts[1] - 1, starts[1], starts[1] + 1, starts[5], starts[20] + 7, size - 2, size - 1};
    for (long first : edges) {
      for (long last : edges) {
        if (last < first) {
          continue;
        }
        Slice slice = reconstruction.slice(first, last).orElseThrow();
        int index = terms.indexOf(slice.terms().get(0));
        // the same term can appear twice; find the occurrence the slice starts with
        while (starts[index] + terms.get(index).unpackedLength() <= first) {
          index = terms.subList(index + 1, terms.size()).indexOf(slice.terms().get(0)) + index + 1;
        }
        assertEquals(terms.subList(index, index + slice.terms().size()), slice.terms());
        assertEquals("starts at the first byte", first, starts[index] + slice.offsetIntoFirstRange());
        long bytes = slice.terms().stream().mapToLong(Term::unpackedLength).sum() - slice.offsetIntoFirstRange();
        long wanted = last - first + 1;
        Term lastTerm = slice.terms().get(slice.terms().size() - 1);
        assertTrue("covers the window", bytes >= wanted);
        assertTrue("only the last term is trimmed", bytes - wanted < lastTerm.unpackedLength());
        for (Term term : slice.terms()) {
          assertTrue(slice.xorbs().get(term.xorbHash()).stream()
              .anyMatch(part -> part.chunkStart() <= term.chunkStart() && term.chunkEnd() <= part.chunkEnd()));
        }
      }
    }
  }

  @Test
  public void windowsAfterTheEndAreEmpty() throws IOException {
    XetReconstruction reconstruction = load("reconstruction-v2-pytorch_model.bin.json");
    assertFalse(reconstruction.slice(3561811, Long.MAX_VALUE).isPresent());
    assertTrue(reconstruction.slice(3561810, Long.MAX_VALUE).isPresent());
  }

  @Test
  public void storesWithoutUrls() throws IOException {
    XetReconstruction reconstruction = load("reconstruction-v2-tf_model.h5.json");
    JsonNode stored = reconstruction.toStoredJson();
    assertFalse(stored.toString().contains("url"));
    XetReconstruction again = XetReconstruction.parse(objectMapper.readTree(stored.toString()));
    assertEquals(reconstruction.terms(), again.terms());
    assertEquals(reconstruction.xorbs(), again.xorbs());
    assertTrue(again.fetches().isEmpty());
  }

  @Test
  public void answersInBothVersions() throws IOException {
    XetReconstruction reconstruction = load("reconstruction-v2-pytorch_model.bin.json");
    Slice slice = reconstruction.slice(0, Long.MAX_VALUE).orElseThrow();
    for (int version : new int[]{1, 2}) {
      JsonNode json = objectMapper.readTree(slice.toJson(version, (hash, part) -> "http://nexus/" + part.first())
          .toString());
      XetReconstruction answered = XetReconstruction.parse(json);
      assertEquals(reconstruction.terms(), answered.terms());
      assertEquals("http://nexus/904006", answered.fetches().get(0).url());
      assertEquals(reconstruction.xorbs(), answered.xorbs());
    }
  }

  @Test
  public void rejectsTermsWithoutXorbRange() throws IOException {
    String json = new String(XetReconstructionTest.class.getResourceAsStream("reconstruction-v2-pytorch_model.bin.json")
        .readAllBytes()).replace("\"start\":21,\"end\":55", "\"start\":22,\"end\":55");
    assertThrows(IOException.class, () -> XetReconstruction.parse(objectMapper.readTree(json)));
  }

  private XetReconstruction load(final String name) throws IOException {
    try (InputStream in = XetReconstructionTest.class.getResourceAsStream(name)) {
      return XetReconstruction.parse(objectMapper.readTree(in));
    }
  }
}
