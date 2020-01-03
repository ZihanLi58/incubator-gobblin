package org.apache.gobblin.util;

import java.io.IOException;
import java.util.regex.PatternSyntaxException;
import org.apache.hadoop.fs.GlobPattern;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;


public class GlobFilter implements PathFilter {
  private final static PathFilter DEFAULT_FILTER = new PathFilter() {
    @Override
    public boolean accept(Path file) {
      return true;
    }
  };

  private PathFilter userFilter = DEFAULT_FILTER;
  private GlobPattern pattern;

  /**
   * Creates a glob filter with the specified file pattern.
   *
   * @param filePattern the file pattern.
   * @throws IOException thrown if the file pattern is incorrect.
   */
  public GlobFilter(String filePattern) throws IOException {
    init(filePattern, DEFAULT_FILTER);
  }

  /**
   * Creates a glob filter with the specified file pattern and an user filter.
   *
   * @param filePattern the file pattern.
   * @param filter user filter in addition to the glob pattern.
   * @throws IOException thrown if the file pattern is incorrect.
   */
  public GlobFilter(String filePattern, PathFilter filter) throws IOException {
    init(filePattern, filter);
  }

  void init(String filePattern, PathFilter filter) throws IOException {
    try {
      userFilter = filter;
      pattern = new GlobPattern(filePattern);
    }
    catch (PatternSyntaxException e) {
      // Existing code expects IOException startWith("Illegal file pattern")
      throw new IOException("Illegal file pattern: "+ e.getMessage(), e);
    }
  }

  boolean hasPattern() {
    return pattern.hasWildcard();
  }

  @Override
  public boolean accept(Path path) {
    return pattern.matches(path.getName()) && userFilter.accept(path);
  }
}
