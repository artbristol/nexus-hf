/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static java.util.Objects.requireNonNull;

/**
 * How to reconstruct a whole file from XET storage: its terms (chunk ranges of xorbs, in file order), and the byte
 * ranges of the xorbs that hold them.
 *
 * Clients ask for a file in windows whose sizes depend on how fast their downloads go. They are answered from the
 * reconstruction of the whole file, sliced to the window ({@link #slice}): so that the xorb ranges clients fetch, and
 * this repository caches, are the same whatever the windows.
 */
public final class XetReconstruction
{
  private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  /**
   * Chunks {@code [chunkStart, chunkEnd)} of a xorb, which make {@code unpackedLength} bytes of the file.
   */
  public record Term(String xorbHash, int chunkStart, int chunkEnd, long unpackedLength)
  {
  }

  /**
   * A range of a xorb: chunks {@code [chunkStart, chunkEnd)}, serialized at bytes {@code first} to {@code last}.
   */
  public record Part(int chunkStart, int chunkEnd, long first, long last)
  {
    public int chunks() {
      return chunkEnd - chunkStart;
    }

    boolean contains(final Term term) {
      return chunkStart <= term.chunkStart() && term.chunkEnd() <= chunkEnd;
    }
  }

  /**
   * A presigned URL of XET storage for one or more ranges of a xorb, which must be requested together, with exactly
   * these ranges.
   */
  public record Fetch(String url, String xorbHash, List<Part> parts)
  {
  }

  /**
   * What a client gets for a window of the file.
   *
   * @param offsetIntoFirstRange bytes of the first term before the window
   */
  public record Slice(long offsetIntoFirstRange, List<Term> terms, Map<String, List<Part>> xorbs)
  {
    /**
     * The reconstruction response of the given API version, with the URL of each xorb range.
     */
    public ObjectNode toJson(final int version, final BiFunction<String, Part, String> url) {
      ObjectNode body = JSON.objectNode();
      body.put("offset_into_first_range", offsetIntoFirstRange);
      ArrayNode termsJson = body.putArray("terms");
      for (Term term : terms) {
        ObjectNode termJson = termsJson.addObject();
        termJson.put("hash", term.xorbHash());
        termJson.put("unpacked_length", term.unpackedLength());
        termJson.set("range", range(term.chunkStart(), term.chunkEnd()));
      }
      ObjectNode xorbsJson = body.putObject(version == 1 ? "fetch_info" : "xorbs");
      xorbs.forEach((hash, parts) -> {
        ArrayNode entries = xorbsJson.putArray(hash);
        for (Part part : parts) {
          ObjectNode entry = entries.addObject();
          if (version == 1) {
            entry.set("range", range(part.chunkStart(), part.chunkEnd()));
            entry.put("url", url.apply(hash, part));
            entry.set("url_range", range(part.first(), part.last()));
          }
          else {
            // one range per URL, which the client fetches with a single-range request
            entry.put("url", url.apply(hash, part));
            entry.putArray("ranges").add(rangeDescriptor(part));
          }
        }
      });
      return body;
    }
  }

  private final List<Term> terms;

  private final Map<String, List<Part>> xorbs;

  private final List<Fetch> fetches;

  private final long size;

  private XetReconstruction(final List<Term> terms, final Map<String, List<Part>> xorbs, final List<Fetch> fetches) {
    this.terms = List.copyOf(terms);
    this.xorbs = xorbs;
    this.fetches = List.copyOf(fetches);
    this.size = terms.stream().mapToLong(Term::unpackedLength).sum();
  }

  /**
   * Reads the reconstruction of a whole file: a response of XET storage (version 1 with {@code fetch_info}, or 2 with
   * {@code xorbs}), or what {@link #toStoredJson()} wrote.
   */
  public static XetReconstruction parse(final JsonNode body) throws IOException {
    if (body.path("offset_into_first_range").asLong(0) != 0) {
      throw new IOException("Reconstruction of a whole file with an offset");
    }
    List<Term> terms = new ArrayList<>();
    for (JsonNode term : body.path("terms")) {
      String hash = hash(term.path("hash").asText());
      JsonNode range = term.path("range");
      long unpackedLength = term.path("unpacked_length").asLong(-1);
      if (!range.path("start").canConvertToInt() || !range.path("end").canConvertToInt() || unpackedLength < 0
          || range.path("start").asInt() >= range.path("end").asInt())
      {
        throw new IOException("Invalid term in reconstruction: " + term);
      }
      terms.add(new Term(hash, range.path("start").asInt(), range.path("end").asInt(), unpackedLength));
    }

    List<Fetch> fetches = new ArrayList<>();
    if (body.has("fetch_info")) {
      for (Iterator<Entry<String, JsonNode>> it = body.path("fetch_info").fields(); it.hasNext(); ) {
        Entry<String, JsonNode> xorb = it.next();
        for (JsonNode fetch : xorb.getValue()) {
          fetches.add(new Fetch(fetch.path("url").asText(""), hash(xorb.getKey()),
              List.of(part(fetch.path("range"), fetch.path("url_range")))));
        }
      }
    }
    else {
      for (Iterator<Entry<String, JsonNode>> it = body.path("xorbs").fields(); it.hasNext(); ) {
        Entry<String, JsonNode> xorb = it.next();
        for (JsonNode fetch : xorb.getValue()) {
          List<Part> parts = new ArrayList<>();
          for (JsonNode range : fetch.path("ranges")) {
            parts.add(part(range.path("chunks"), range.path("bytes")));
          }
          fetches.add(new Fetch(fetch.path("url").asText(""), hash(xorb.getKey()), parts));
        }
      }
    }

    Map<String, List<Part>> xorbs = new LinkedHashMap<>();
    for (Fetch fetch : fetches) {
      xorbs.computeIfAbsent(fetch.xorbHash(), h -> new ArrayList<>()).addAll(fetch.parts());
    }
    for (Term term : terms) {
      part(xorbs, term); // fails if missing
    }
    return new XetReconstruction(terms, xorbs, fetches.stream().filter(f -> !f.url().isEmpty()).toList());
  }

  /**
   * Size of the file.
   */
  public long size() {
    return size;
  }

  public List<Term> terms() {
    return terms;
  }

  /**
   * The xorb ranges, by xorb.
   */
  public Map<String, List<Part>> xorbs() {
    return xorbs;
  }

  /**
   * The presigned URLs of a reconstruction from XET storage.
   */
  public List<Fetch> fetches() {
    return fetches;
  }

  /**
   * The terms covering bytes {@code first} to {@code last} (inclusive; clamped to the file), with the xorb ranges
   * they need: whole terms, from the one containing the first byte, with the offset of that byte, to the one
   * containing the last byte, which the client trims.
   *
   * @return nothing if the window starts at or after the end of the file
   */
  public Optional<Slice> slice(final long first, final long last) throws IOException {
    if (first < 0 || first >= size || last < first) {
      return Optional.empty();
    }
    List<Term> selected = new ArrayList<>();
    long offset = 0;
    long termStart = 0;
    for (Term term : terms) {
      long termEnd = termStart + term.unpackedLength();
      if (termEnd > first && termStart <= last) {
        if (selected.isEmpty()) {
          offset = first - termStart;
        }
        selected.add(term);
      }
      if (termEnd > last) {
        break;
      }
      termStart = termEnd;
    }
    Map<String, List<Part>> parts = new LinkedHashMap<>();
    for (Term term : selected) {
      Part part = part(xorbs, term);
      List<Part> xorbParts = parts.computeIfAbsent(term.xorbHash(), h -> new ArrayList<>());
      if (!xorbParts.contains(part)) {
        xorbParts.add(part);
      }
    }
    return Optional.of(new Slice(offset, selected, parts));
  }

  /**
   * The whole file, without URLs, to be stored.
   */
  public ObjectNode toStoredJson() {
    ObjectNode body = new Slice(0, terms, xorbs).toJson(2, (hash, part) -> "");
    body.path("xorbs").forEach(entries -> entries.forEach(entry -> ((ObjectNode) entry).remove("url")));
    return body;
  }

  private static Part part(final Map<String, List<Part>> xorbs, final Term term) throws IOException {
    for (Part part : xorbs.getOrDefault(term.xorbHash(), List.of())) {
      if (part.contains(term)) {
        return part;
      }
    }
    throw new IOException("No xorb range in reconstruction for " + term);
  }

  private static Part part(final JsonNode chunks, final JsonNode bytes) throws IOException {
    if (!chunks.path("start").canConvertToInt() || !chunks.path("end").canConvertToInt()
        || !bytes.path("start").canConvertToLong() || !bytes.path("end").canConvertToLong())
    {
      throw new IOException("Invalid xorb range in reconstruction");
    }
    Part part = new Part(chunks.path("start").asInt(), chunks.path("end").asInt(), bytes.path("start").asLong(),
        bytes.path("end").asLong());
    if (part.chunks() <= 0 || part.first() < 0 || part.last() < part.first()) {
      throw new IOException("Invalid xorb range in reconstruction: " + part);
    }
    return part;
  }

  private static String hash(final String value) throws IOException {
    if (!HASH.matcher(requireNonNull(value)).matches()) {
      throw new IOException("Invalid hash in reconstruction: " + value);
    }
    return value;
  }

  private static ObjectNode range(final long start, final long end) {
    return JSON.objectNode().put("start", start).put("end", end);
  }

  private static ObjectNode rangeDescriptor(final Part part) {
    ObjectNode descriptor = JSON.objectNode();
    descriptor.set("chunks", range(part.chunkStart(), part.chunkEnd()));
    descriptor.set("bytes", range(part.first(), part.last()));
    return descriptor;
  }
}
