/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class MultipartByteRangesTest
{
  /**
   * The example of RFC 9110, section 14.6.
   */
  private static final String CONTENT_TYPE = "multipart/byteranges; boundary=THIS_STRING_SEPARATES";

  private static final String BODY = """
      --THIS_STRING_SEPARATES\r
      Content-Type: application/pdf\r
      Content-Range: bytes 500-999/8000\r
      \r
      %s\r
      --THIS_STRING_SEPARATES\r
      Content-Type: application/pdf\r
      Content-Range: bytes 7000-7999/8000\r
      \r
      %s\r
      --THIS_STRING_SEPARATES--\r
      """;

  private final String first = "a".repeat(499) + "\n"; // a line break inside the data must not matter

  private final String second = "\r\n--THIS_STRING_SEPARATES\r\n".repeat(40);

  @Test
  public void readsTheBoundary() {
    assertEquals("THIS_STRING_SEPARATES", MultipartByteRanges.boundary(CONTENT_TYPE));
    assertEquals("a b", MultipartByteRanges.boundary("multipart/byteranges; boundary=\"a b\""));
    assertNull(MultipartByteRanges.boundary("application/octet-stream"));
    assertNull(MultipartByteRanges.boundary(null));
  }

  @Test
  public void readsThePartsByTheirContentRange() throws IOException {
    List<String> parts = new ArrayList<>();
    MultipartByteRanges.read(stream(BODY.formatted(first, second.substring(0, 1000))), "THIS_STRING_SEPARATES",
        (start, end, data) -> parts.add(start + "-" + end + ":" + new String(data.readAllBytes(), StandardCharsets.ISO_8859_1)));
    assertEquals(List.of("500-999:" + first, "7000-7999:" + second.substring(0, 1000)), parts);
  }

  @Test
  public void skipsWhatAConsumerDoesNotRead() throws IOException {
    List<Long> starts = new ArrayList<>();
    MultipartByteRanges.read(stream(BODY.formatted(first, second.substring(0, 1000))), "THIS_STRING_SEPARATES",
        (start, end, data) -> starts.add(start));
    assertEquals(List.of(500L, 7000L), starts);
  }

  @Test
  public void rejectsABodyCutShort() {
    String body = BODY.formatted(first, second.substring(0, 1000));
    assertThrows(IOException.class, () -> MultipartByteRanges.read(stream(body.substring(0, body.length() - 30)),
        "THIS_STRING_SEPARATES", (start, end, data) -> data.readAllBytes()));
    assertThrows(IOException.class, () -> MultipartByteRanges.read(stream(body.substring(0, 700)),
        "THIS_STRING_SEPARATES", (start, end, data) -> data.readAllBytes()));
  }

  @Test
  public void rejectsAPartWithoutContentRange() {
    String body = BODY.formatted(first, second.substring(0, 1000)).replaceFirst("Content-Range: bytes 500-999/8000\r\n", "");
    assertThrows(IOException.class, () -> MultipartByteRanges.read(stream(body), "THIS_STRING_SEPARATES",
        (start, end, data) -> data.readAllBytes()));
  }

  private static ByteArrayInputStream stream(final String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1));
  }
}
