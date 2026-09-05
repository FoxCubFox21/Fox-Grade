// Move an original mod jar into mods-backup/ before Fox-Grade writes a ported one in its place.
//
// Rules:
//   · nothing is deleted, ever — a jar is moved, and only after the destination directory exists
//   · a name clash never overwrites: we suffix with .1, .2, ... until a name is free. If Fox-Grade
//     ports a mod twice in two different launches, we keep both originals.
//   · if the move fails for any reason, the caller sees an exception and the pipeline can decline
//     to write the ported jar. A backup we could not create is a good reason to not touch the
//     original in the first place.
package foxgrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class BackupService {
  private final Path backupDir;

  public BackupService(Path backupDir) { this.backupDir = backupDir; }

  public Path moveToBackup(Path jar) throws IOException {
    Files.createDirectories(backupDir);
    String name = jar.getFileName().toString();
    Path dest = backupDir.resolve(name);
    int n = 0;
    while (Files.exists(dest)) {
      n++;
      int dot = name.lastIndexOf('.');
      String stem = dot > 0 ? name.substring(0, dot) : name;
      String ext = dot > 0 ? name.substring(dot) : "";
      dest = backupDir.resolve(stem + "." + n + ext);
    }
    Files.move(jar, dest, StandardCopyOption.ATOMIC_MOVE);
    return dest;
  }
}
