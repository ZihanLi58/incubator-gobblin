package org.apache.gobblin.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;


public class Globber {
  public static final Log LOG = LogFactory.getLog(Globber.class.getName());

  private final FileSystem fs;
  private final Path pathPattern;
  private final PathFilter filter;

  public Globber(FileSystem fs, Path pathPattern, PathFilter filter) {
    this.fs = fs;
    this.pathPattern = pathPattern;
    this.filter = filter;
  }


  private FileStatus getFileStatus(Path path) throws IOException {
    try {
      return fs.getFileStatus(path);
    } catch (FileNotFoundException | RemoteException e) {
      return null;
    }
  }

  private FileStatus[] listStatus(Path path) throws IOException {
    try {
      return fs.listStatus(path);
    } catch (FileNotFoundException | RemoteException e) {
      return new FileStatus[0];
    }
  }

  private Path fixRelativePart(Path path) {
    if (path.isUriPathAbsolute()) {
      return path;
    } else {
      return new Path(this.fs.getWorkingDirectory(), path);
    }
  }

  /**
   * Convert a path component that contains backslash ecape sequences to a
   * literal string.  This is necessary when you want to explicitly refer to a
   * path that contains globber metacharacters.
   */
  private static String unescapePathComponent(String name) {
    return name.replaceAll("\\\\(.)", "$1");
  }

  /**
   * Translate an absolute path into a list of path components.
   * We merge double slashes into a single slash here.
   * POSIX root path, i.e. '/', does not get an entry in the list.
   */
  private static List<String> getPathComponents(String path) {
    ArrayList<String> ret = new ArrayList<String>();
    for (String component : path.split(Path.SEPARATOR)) {
      if (!component.isEmpty()) {
        ret.add(component);
      }
    }
    return ret;
  }

  private String schemeFromPath(Path path) {
    String scheme = path.toUri().getScheme();
    if (scheme == null) {
      scheme = fs.getUri().getScheme();
    }
    return scheme;
  }

  private String authorityFromPath(Path path) {
    String authority = path.toUri().getAuthority();
    if (authority == null) {
      authority = fs.getUri().getAuthority();
    }
    return authority ;
  }

  public FileStatus[] glob() throws IOException {
    // First we get the scheme and authority of the pattern that was passed
    // in.
    String scheme = schemeFromPath(pathPattern);
    String authority = authorityFromPath(pathPattern);

    // Next we strip off everything except the pathname itself, and expand all
    // globs.  Expansion is a process which turns "grouping" clauses,
    // expressed as brackets, into separate path patterns.
    String pathPatternString = pathPattern.toUri().getPath();
    List<String> flattenedPatterns = GlobExpander.expand(pathPatternString);

    // Now loop over all flattened patterns.  In every case, we'll be trying to
    // match them to entries in the filesystem.
    ArrayList<FileStatus> results =
        new ArrayList<FileStatus>(flattenedPatterns.size());
    boolean sawWildcard = false;
    for (String flatPattern : flattenedPatterns) {
      // Get the absolute path for this flattened pattern.  We couldn't do
      // this prior to flattening because of patterns like {/,a}, where which
      // path you go down influences how the path must be made absolute.
      Path absPattern = fixRelativePart(new Path(
          flatPattern.isEmpty() ? Path.CUR_DIR : flatPattern));
      // Now we break the flattened, absolute pattern into path components.
      // For example, /a/*/c would be broken into the list [a, *, c]
      List<String> components =
          getPathComponents(absPattern.toUri().getPath());
      // Starting out at the root of the filesystem, we try to match
      // filesystem entries against pattern components.
      ArrayList<FileStatus> candidates = new ArrayList<FileStatus>(1);
      if (Path.WINDOWS && !components.isEmpty()
          && Path.isWindowsAbsolutePath(absPattern.toUri().getPath(), true)) {
        // On Windows the path could begin with a drive letter, e.g. /E:/foo.
        // We will skip matching the drive letter and start from listing the
        // root of the filesystem on that drive.
        String driveLetter = components.remove(0);
        candidates.add(new FileStatus(0, true, 0, 0, 0, new Path(scheme,
            authority, Path.SEPARATOR + driveLetter + Path.SEPARATOR)));
      } else {
        candidates.add(new FileStatus(0, true, 0, 0, 0,
            new Path(scheme, authority, Path.SEPARATOR)));
      }

      for (int componentIdx = 0; componentIdx < components.size();
          componentIdx++) {
        ArrayList<FileStatus> newCandidates =
            new ArrayList<FileStatus>(candidates.size());
        GlobFilter globFilter = new GlobFilter(components.get(componentIdx));
        String component = unescapePathComponent(components.get(componentIdx));
        if (globFilter.hasPattern()) {
          sawWildcard = true;
        }
        if (candidates.isEmpty() && sawWildcard) {
          // Optimization: if there are no more candidates left, stop examining
          // the path components.  We can only do this if we've already seen
          // a wildcard component-- otherwise, we still need to visit all path
          // components in case one of them is a wildcard.
          break;
        }
        if ((componentIdx < components.size() - 1) &&
            (!globFilter.hasPattern())) {
          // Optimization: if this is not the terminal path component, and we
          // are not matching against a glob, assume that it exists.  If it
          // doesn't exist, we'll find out later when resolving a later glob
          // or the terminal path component.
          for (FileStatus candidate : candidates) {
            candidate.setPath(new Path(candidate.getPath(), component));
          }
          continue;
        }
        for (FileStatus candidate : candidates) {
          if (globFilter.hasPattern()) {
            FileStatus[] children = listStatus(candidate.getPath());
            if (children.length == 1) {
              // If we get back only one result, this could be either a listing
              // of a directory with one entry, or it could reflect the fact
              // that what we listed resolved to a file.
              //
              // Unfortunately, we can't just compare the returned paths to
              // figure this out.  Consider the case where you have /a/b, where
              // b is a symlink to "..".  In that case, listing /a/b will give
              // back "/a/b" again.  If we just went by returned pathname, we'd
              // incorrectly conclude that /a/b was a file and should not match
              // /a/*/*.  So we use getFileStatus of the path we just listed to
              // disambiguate.
              if (!getFileStatus(candidate.getPath()).isDirectory()) {
                continue;
              }
            }
            for (FileStatus child : children) {
              // Set the child path based on the parent path.
              child.setPath(new Path(candidate.getPath(),
                  child.getPath().getName()));
              if (globFilter.accept(child.getPath())) {
                newCandidates.add(child);
              }
            }
          } else {
            // When dealing with non-glob components, use getFileStatus
            // instead of listStatus.  This is an optimization, but it also
            // is necessary for correctness in HDFS, since there are some
            // special HDFS directories like .reserved and .snapshot that are
            // not visible to listStatus, but which do exist.  (See HADOOP-9877)
            FileStatus childStatus = getFileStatus(
                new Path(candidate.getPath(), component));
            if (childStatus != null) {
              newCandidates.add(childStatus);
            }
          }
        }
        candidates = newCandidates;
      }
      for (FileStatus status : candidates) {
        // HADOOP-3497 semantics: the user-defined filter is applied at the
        // end, once the full path is built up.
        if (filter.accept(status.getPath())) {
          results.add(status);
        }
      }
    }
    /*
     * When the input pattern "looks" like just a simple filename, and we
     * can't find it, we return null rather than an empty array.
     * This is a special case which the shell relies on.
     *
     * To be more precise: if there were no results, AND there were no
     * groupings (aka brackets), and no wildcards in the input (aka stars),
     * we return null.
     */
    if ((!sawWildcard) && results.isEmpty() &&
        (flattenedPatterns.size() <= 1)) {
      return null;
    }
    return results.toArray(new FileStatus[0]);
  }
}
