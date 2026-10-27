/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Against a real xorb range from the Hub's XET storage: the 8 chunks (13 to 21, bytes 486722-904005) of xorb
 * b8cfb9c1f6d87757ddb987f60b3f039fa0462d47dedb9a789cf89491a155a55f that make up {@code model.safetensors} of
 * hf-internal-testing/tiny-random-gpt2, 453864 bytes, in its reconstruction term's {@code unpacked_length} too.
 */
public class XorbChunksTest
{
  private static final int CHUNKS = 8;

  private static final long FILE_SIZE = 453864;

  private final byte[] range = load();

  @Test
  public void acceptsARealRangeAndSumsItsChunks() throws IOException {
    assertEquals(FILE_SIZE, XorbChunks.check(new ByteArrayInputStream(range), range.length, CHUNKS));
  }

  @Test
  public void rejectsATruncatedRange() {
    byte[] truncated = Arrays.copyOf(range, range.length - 1);
    assertThrows(IOException.class,
        () -> XorbChunks.check(new ByteArrayInputStream(truncated), range.length, CHUNKS));
    assertThrows(IOException.class,
        () -> XorbChunks.check(new ByteArrayInputStream(truncated), truncated.length, CHUNKS));
  }

  @Test
  public void rejectsTrailingBytes() {
    byte[] padded = Arrays.copyOf(range, range.length + 1);
    assertThrows(IOException.class, () -> XorbChunks.check(new ByteArrayInputStream(padded), padded.length, CHUNKS));
    assertThrows(IOException.class, () -> XorbChunks.check(new ByteArrayInputStream(padded), range.length, CHUNKS));
  }

  @Test
  public void rejectsTheWrongNumberOfChunks() {
    assertThrows(IOException.class,
        () -> XorbChunks.check(new ByteArrayInputStream(range), range.length, CHUNKS - 1));
    assertThrows(IOException.class,
        () -> XorbChunks.check(new ByteArrayInputStream(range), range.length, CHUNKS + 1));
  }

  @Test
  public void rejectsACorruptHeader() {
    byte[] corrupt = range.clone();
    corrupt[0] = 1; // version
    assertThrows(IOException.class, () -> XorbChunks.check(new ByteArrayInputStream(corrupt), range.length, CHUNKS));
  }

  @Test
  public void rejectsSomethingElse() {
    byte[] html = "<html><body>Access denied</body></html>".getBytes();
    assertThrows(IOException.class, () -> XorbChunks.check(new ByteArrayInputStream(html), html.length, 1));
  }

  private static byte[] load() {
    try (InputStream in = XorbChunksTest.class.getResourceAsStream("xorb-range.bin")) {
      return in.readAllBytes();
    }
    catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
