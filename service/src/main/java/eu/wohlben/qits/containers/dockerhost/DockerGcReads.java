package eu.wohlben.qits.containers.dockerhost;

import eu.wohlben.qits.containers.control.ContainersDriver.DiskUsage;
import eu.wohlben.qits.containers.control.ContainersDriver.ImageSummary;
import eu.wohlben.qits.containers.control.ContainersDriver.UsageLine;
import eu.wohlben.qits.containers.control.ContainersDriver.VolumeDetail;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading what the garbage collection's docker calls print — pure functions, no I/O, no daemon.
 *
 * <p>It is a class of its own beside {@link DockerContainersDriver} for the reason {@code
 * DockerArgv} is one beside the driver: the assembling and the reading are the two halves that can
 * be asserted without a host, and keeping them out of the call layer is what makes the assertions
 * possible. Every format these read is a Go template spelled in {@code DockerArgv}, and every
 * sample in {@code DockerGcReadsTest} was taken off docker 29.7.2 rather than imagined.
 *
 * <p><b>Docker prints sizes for people and this service needs numbers.</b> {@code 308.3GB},
 * {@code 262.1kB}, {@code 1.492GB}, {@code 0B} — decimal units, one decimal place or three, a unit
 * that may be absent. {@link #bytes} is the one reader of all of them, and it answers 0 for
 * anything it cannot read, because a size that could not be parsed must not become a large number
 * somebody's threshold acts on.
 */
final class DockerGcReads {

  /** How docker spells a value it does not have. */
  private static final String NO_VALUE = "<no value>";

  /** How docker spells an image with no repository or no tag. */
  private static final String NONE = "<none>";

  /** A human size: a number, optional space, an optional unit. Anything after it is ignored. */
  private static final Pattern SIZE =
      Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]*)");

  /**
   * The instant part of {@code docker image ls}'s {@code CreatedAt}:
   * {@code 2026-08-18 04:46:43 +0200 CEST}. The trailing zone NAME is deliberately not read — it is
   * ambiguous between zones and the offset in front of it already says the instant exactly.
   */
  private static final Pattern IMAGE_CREATED =
      Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4})");

  private static final DateTimeFormatter IMAGE_CREATED_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss Z", Locale.ROOT);

  /** The lines a {@code prune} or a {@code du} summarises itself with. */
  private static final Set<String> SUMMARY_KEYS =
      new LinkedHashSet<>(
          List.of("total reclaimed space", "total", "reclaimable", "shared", "private"));

  /** How much of a summary is carried back to a caller. It is four short lines at most. */
  private static final int SUMMARY_MAX_CHARS = 2_000;

  private DockerGcReads() {}

  // --- sizes -------------------------------------------------------------------------------------

  /**
   * A human size as bytes. Decimal units, because that is what docker prints: {@code 1kB} is a
   * thousand and not 1024. The binary spellings are read as well, since buildkit prints those in
   * some versions, and an unreadable value is 0 rather than a guess.
   */
  static long bytes(String human) {
    if (human == null) {
      return 0;
    }
    Matcher matcher = SIZE.matcher(human.strip());
    if (!matcher.find()) {
      return 0;
    }
    double value;
    try {
      value = Double.parseDouble(matcher.group(1));
    } catch (NumberFormatException e) {
      return 0;
    }
    return Math.round(value * unit(matcher.group(2)));
  }

  /** What one unit is worth. An unknown unit is a byte, which under-counts rather than over. */
  private static double unit(String suffix) {
    return switch (suffix.toLowerCase(Locale.ROOT)) {
      case "k", "kb" -> 1e3;
      case "m", "mb" -> 1e6;
      case "g", "gb" -> 1e9;
      case "t", "tb" -> 1e12;
      case "p", "pb" -> 1e15;
      case "kib" -> 1024d;
      case "mib" -> 1024d * 1024;
      case "gib" -> 1024d * 1024 * 1024;
      case "tib" -> 1024d * 1024 * 1024 * 1024;
      default -> 1d;
    };
  }

  /** A count docker printed, or 0 for one it did not. */
  private static long count(String value) {
    try {
      return Long.parseLong(present(value));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  // --- docker system df --------------------------------------------------------------------------

  /**
   * The four stores of {@code docker system df}.
   *
   * <p>A type the output did not carry answers as an all-zero line rather than as null: the wire
   * has four members and a caller drawing them needs four, and "docker printed nothing for build
   * cache" and "the build cache is empty" are the same statement from a daemon that answered.
   */
  static DiskUsage diskUsage(String output) {
    Map<String, UsageLine> lines = new LinkedHashMap<>();
    for (String line : linesOf(output)) {
      String[] fields = line.split("\\|", 5);
      if (fields.length < 5) {
        continue;
      }
      lines.put(
          present(fields[0]).toLowerCase(Locale.ROOT),
          new UsageLine(
              count(fields[1]), count(fields[2]), bytes(present(fields[3])), bytes(present(fields[4]))));
    }
    return new DiskUsage(
        lines.getOrDefault("images", UsageLine.EMPTY),
        lines.getOrDefault("containers", UsageLine.EMPTY),
        lines.getOrDefault("local volumes", UsageLine.EMPTY),
        lines.getOrDefault("build cache", UsageLine.EMPTY));
  }

  // --- docker image ls ---------------------------------------------------------------------------

  /**
   * Every image, with the {@code repository:tag} lines folded onto their id.
   *
   * <p>Docker prints one line per tag, so a two-tag image arrives twice; the fold is what makes
   * "this image is dangling" answerable at all, since it means "no line gave it a tag" rather than
   * "this line had none". Order is the listing's, which is newest first.
   */
  static List<ImageSummary> images(String output) {
    Map<String, Draft> drafts = new LinkedHashMap<>();
    for (String line : linesOf(output)) {
      String[] fields = line.split("\\|", 5);
      if (fields.length < 5) {
        continue;
      }
      String id = present(fields[0]);
      if (id.isEmpty()) {
        continue;
      }
      Draft draft =
          drafts.computeIfAbsent(
              id, key -> new Draft(bytes(present(fields[4])), imageCreatedAt(present(fields[3]))));
      String repository = present(fields[1]);
      String tag = present(fields[2]);
      if (!repository.isEmpty()
          && !NONE.equals(repository)
          && !tag.isEmpty()
          && !NONE.equals(tag)) {
        draft.tags.add(repository + ":" + tag);
      }
    }
    List<ImageSummary> images = new ArrayList<>();
    drafts.forEach(
        (id, draft) ->
            images.add(new ImageSummary(id, List.copyOf(draft.tags), draft.sizeBytes, draft.createdAt)));
    return List.copyOf(images);
  }

  /** When docker says the image was built, or null for a value that would not read. */
  static Instant imageCreatedAt(String value) {
    Matcher matcher = IMAGE_CREATED.matcher(value == null ? "" : value.strip());
    if (!matcher.find()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(matcher.group(1), IMAGE_CREATED_FORMAT).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** One image while its lines are still arriving. */
  private static final class Draft {
    private final Set<String> tags = new LinkedHashSet<>();
    private final long sizeBytes;
    private final Instant createdAt;

    private Draft(long sizeBytes, Instant createdAt) {
      this.sizeBytes = sizeBytes;
      this.createdAt = createdAt;
    }
  }

  // --- docker volume inspect ---------------------------------------------------------------------

  /**
   * A volume's creation time and labels — the first line, then one {@code k=v} per line.
   *
   * <p>A label with no {@code =} is skipped rather than kept with an empty key: the range template
   * always writes the separator, so a line without one is not a label docker printed.
   */
  static VolumeDetail volumeDetail(String name, String output) {
    List<String> lines = linesOf(output);
    Instant createdAt = lines.isEmpty() ? null : volumeCreatedAt(lines.getFirst());
    Map<String, String> labels = new LinkedHashMap<>();
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      int split = line.indexOf('=');
      if (split > 0) {
        labels.put(line.substring(0, split), line.substring(split + 1));
      }
    }
    return new VolumeDetail(name, Map.copyOf(labels), createdAt);
  }

  /** Docker prints a volume's {@code CreatedAt} as RFC 3339 with an offset. */
  static Instant volumeCreatedAt(String value) {
    String text = present(value);
    if (text.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  // --- build cache -------------------------------------------------------------------------------

  /**
   * What a prune reclaimed.
   *
   * <p><b>Two spellings, both live.</b> Docker up to 28 ends a {@code builder prune} with
   * {@code Total reclaimed space: 1.2GB}; docker 29's buildx prune ends it with {@code Total:} and
   * a tab — measured on 29.7.2. Reading only one of them would report every prune as having freed
   * nothing on half the hosts the platform runs.
   */
  static long reclaimedBytes(String output) {
    String explicit = summaryValue(output, "total reclaimed space");
    return explicit == null ? bytes(summaryValue(output, "total")) : bytes(explicit);
  }

  /**
   * What a {@code du} says a cache could give back. {@code Reclaimable} when it printed one,
   * {@code Total} otherwise — the second is the whole cache, which is the honest fallback for a
   * version that names no reclaimable share.
   */
  static long reclaimableBytes(String output) {
    String reclaimable = summaryValue(output, "reclaimable");
    return reclaimable == null ? bytes(summaryValue(output, "total")) : bytes(reclaimable);
  }

  /**
   * The summary lines of a prune or a {@code du}, joined into one line.
   *
   * <p>The per-record lines above them are dropped: there are thousands of them on a build host,
   * they name cache ids nobody outside buildkit can use, and the four summary lines are the whole
   * of what a caller reports.
   */
  static String cacheSummary(String output) {
    List<String> summary = new ArrayList<>();
    for (String line : linesOf(output)) {
      int colon = line.indexOf(':');
      if (colon > 0 && SUMMARY_KEYS.contains(line.substring(0, colon).strip().toLowerCase(Locale.ROOT))) {
        summary.add(line.substring(0, colon).strip() + ": " + line.substring(colon + 1).strip());
      }
    }
    String joined = String.join("; ", summary);
    return joined.length() <= SUMMARY_MAX_CHARS ? joined : joined.substring(0, SUMMARY_MAX_CHARS);
  }

  /** The value of one summary line, or null when the output carried none. */
  private static String summaryValue(String output, String key) {
    for (String line : linesOf(output)) {
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).strip().equalsIgnoreCase(key)) {
        return line.substring(colon + 1).strip();
      }
    }
    return null;
  }

  // --- the shapes every reader above is built on -------------------------------------------------

  /** Non-blank lines, stripped. What every one of these formats prints one of per thing. */
  static List<String> linesOf(String output) {
    return (output == null ? "" : output)
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  /**
   * A value docker really printed, or the empty string for one it did not. Same belt as the
   * observation reader's, for the same measured reason: Go prints {@code <no value>} for a field
   * the object does not carry, and read back it would be a size, a count or a tag nothing has.
   */
  private static String present(String value) {
    String stripped = value == null ? "" : value.strip();
    return NO_VALUE.equals(stripped) ? "" : stripped;
  }
}
