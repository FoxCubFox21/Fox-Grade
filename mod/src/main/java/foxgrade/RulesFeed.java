package foxgrade;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** Rules and anti-rules published after the mod was built, so an installed copy keeps improving.
 *
 *  <p>Everything Fox-Grade knows about a version ships inside the jar, which means a copy installed today is as good
 *  as it will ever be. That is the wrong shape for this problem: what breaks a port is discovered by someone running
 *  it, and the fix is almost always a table entry — a mixin that must be disabled for one mod, a call that must be
 *  redirected — not new code. Those should reach every instance, including the ones already installed, without
 *  waiting for a release.
 *
 *  <p>So a small feed is fetched and merged on top of the bundled tables. Three properties make that safe enough to
 *  do by default:
 *  <ul>
 *    <li>It can only ever <em>add</em> to what ships. The bundled tables are loaded first and are never replaced, so
 *        a feed that is empty, stale, unreachable or malformed leaves Fox-Grade exactly as it was built.</li>
 *    <li>It is cached, and the cache is used whenever the network is not. Porting still works with the machine
 *        offline; the feed is a refinement, never a dependency.</li>
 *    <li>It carries data, not code: blocklist entries and bridge-table rows, parsed into the same structures the
 *        bundled files parse into. There is no path by which it can introduce behaviour the engine does not have.</li>
 *  </ul>
 *
 *  <p>Switched off with {@code "rulesFeed": false} in {@code fox-grade.config.json}, which stops the request entirely
 *  rather than merely ignoring the answer. */
public final class RulesFeed {
  private RulesFeed() { }

  /** Published from this repository, so the history of every rule is public and reviewable. */
  private static final String URL = "https://raw.githubusercontent.com/FoxCubFox21/Fox-Grade/main/feed/rules-feed.json";

  /** Long enough that a launch never waits on it, short enough that a fix lands the day after it is published. */
  private static final Duration MAX_AGE = Duration.ofHours(20);
  private static final int TIMEOUT_MS = 4000;

  private static volatile JsonObject cached;
  private static volatile boolean loaded;

  /** The feed as a JSON object, or null when there is nothing usable. Fetches at most once per launch. */
  public static synchronized JsonObject get(Path gameDir) {
    if (loaded) return cached;
    loaded = true;
    if (!enabled(gameDir)) return null;
    Path file = gameDir.resolve(".fox-grade").resolve("cache").resolve("rules-feed.json");
    JsonObject onDisk = readFile(file);
    if (onDisk != null && fresh(file)) return cached = onDisk;
    JsonObject fetched = fetch();
    if (fetched != null) {
      try {
        Files.createDirectories(file.getParent());
        Files.writeString(file, new Gson().toJson(fetched), StandardCharsets.UTF_8);
      } catch (IOException cannotCache) { /* a feed that cannot be cached is still usable now */ }
      return cached = fetched;
    }
    // Nothing new: yesterday's answer beats none, and none beats a failed launch.
    return cached = onDisk;
  }

  /** A named section of the feed, or null. */
  public static JsonObject section(Path gameDir, String name) {
    JsonObject feed = get(gameDir);
    if (feed == null || !feed.has(name) || !feed.get(name).isJsonObject()) return null;
    return feed.getAsJsonObject(name);
  }

  private static boolean enabled(Path gameDir) {
    try {
      Path cfg = gameDir.resolve("fox-grade.config.json");
      if (!Files.exists(cfg)) return true;
      JsonObject o = new Gson().fromJson(Files.readString(cfg), JsonObject.class);
      return o == null || !o.has("rulesFeed") || o.get("rulesFeed").getAsBoolean();
    } catch (Exception unreadable) {
      return true;
    }
  }

  private static boolean fresh(Path file) {
    try {
      return Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(MAX_AGE));
    } catch (IOException t) {
      return false;
    }
  }

  private static JsonObject readFile(Path file) {
    try {
      if (!Files.exists(file)) return null;
      return new Gson().fromJson(Files.readString(file), JsonObject.class);
    } catch (Exception unusable) {
      return null;
    }
  }

  private static JsonObject fetch() {
    try {
      HttpURLConnection conn = (HttpURLConnection) URI.create(URL).toURL().openConnection();
      conn.setConnectTimeout(TIMEOUT_MS);
      conn.setReadTimeout(TIMEOUT_MS);
      conn.setRequestProperty("User-Agent", "Fox-Grade/" + FoxGradePreLaunch.VERSION);
      if (conn.getResponseCode() != 200) return null;
      try (var in = conn.getInputStream()) {
        return new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
      }
    } catch (Exception offlineOrBroken) {
      return null;
    }
  }
}
