/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.writer;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Closer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.gobblin.codec.StreamCodec;
import org.apache.gobblin.commit.SpeculativeAttemptAwareConstruct;
import org.apache.gobblin.config.ConfigBuilder;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.configuration.State;
import org.apache.gobblin.dataset.DatasetDescriptor;
import org.apache.gobblin.dataset.Descriptor;
import org.apache.gobblin.dataset.PartitionDescriptor;
import org.apache.gobblin.metadata.types.GlobalMetadata;
import org.apache.gobblin.util.FinalState;
import org.apache.gobblin.util.ForkOperatorUtils;
import org.apache.gobblin.util.HadoopUtils;
import org.apache.gobblin.util.JobConfigurationUtils;
import org.apache.gobblin.util.WriterUtils;
import org.apache.gobblin.util.recordcount.IngestionRecordCountProvider;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.gobblin.util.retry.RetryerFactory.*;


/**
 * An implementation of {@link DataWriter} does the work of setting the output/staging dir
 * and creating the FileSystem instance.
 *
 * @author akshay@nerdwallet.com
 */
public abstract class FsDataWriter<D> implements DataWriter<D>, FinalState, MetadataAwareWriter, SpeculativeAttemptAwareConstruct {

  private static final Logger LOG = LoggerFactory.getLogger(FsDataWriter.class);

  public static final String WRITER_INCLUDE_RECORD_COUNT_IN_FILE_NAMES =
      ConfigurationKeys.WRITER_PREFIX + ".include.record.count.in.file.names";
  public static final String WRITER_RETRY_PREFIX = ConfigurationKeys.WRITER_PREFIX + ".retry.";
  public static final String WRITER_RETRY_ENABLED = WRITER_RETRY_PREFIX + "enabled";
  public static final String FS_WRITER_METRICS_KEY = "fs_writer_metrics";

  protected final State properties;
  protected final String id;
  protected final int numBranches;
  protected final int branchId;
  protected final String fileName;
  protected final FileSystem fs;
  protected final FileContext fileContext;
  protected final Path stagingFile;
  protected final String partitionKey;
  private final GlobalMetadata defaultMetadata;
  protected Path outputFile;
  protected final String allOutputFilesPropName;
  protected final boolean shouldIncludeRecordCountInFileName;
  protected final int bufferSize;
  protected final short replicationFactor;
  protected final long blockSize;
  protected final FsPermission filePermission;
  protected final FsPermission dirPermission;
  protected final Optional<String> group;
  protected final Closer closer = Closer.create();
  protected final Optional<String> writerAttemptIdOptional;
  protected Optional<Long> bytesWritten;
  private final List<StreamCodec> encoders;
  static final Config WRITER_RETRY_DEFAULTS;
  static {
    Map<String, Object> configMap =
        ImmutableMap.<String, Object>builder()
            .put(RETRY_TIME_OUT_MS, TimeUnit.MINUTES.toMillis(2L))   //Overall retry for 2 minutes
            .put(RETRY_INTERVAL_MS, TimeUnit.SECONDS.toMillis(5L)) //Try to retry 5 seconds
            .put(RETRY_MULTIPLIER, 2L) // Muliply by 2 every attempt
            .put(RETRY_TYPE, RetryType.EXPONENTIAL.name())
            .build();
    WRITER_RETRY_DEFAULTS = ConfigFactory.parseMap(configMap);
  };

  public FsDataWriter(FsDataWriterBuilder<?, ?> builder, State properties) throws IOException {
    this.properties = properties;
    this.id = builder.getWriterId();
    this.numBranches = builder.getBranches();
    this.branchId = builder.getBranch();
    this.fileName = builder.getFileName(properties);
    this.writerAttemptIdOptional = Optional.fromNullable(builder.getWriterAttemptId());
    this.encoders = builder.getEncoders();

    Configuration conf = new Configuration();
    // Add all job configuration properties so they are picked up by Hadoop
    JobConfigurationUtils.putStateIntoConfiguration(properties, conf);
    this.fs = WriterUtils.getWriterFS(properties, this.numBranches, this.branchId);
    this.fileContext = FileContext.getFileContext(
            WriterUtils.getWriterFsUri(properties, this.numBranches, this.branchId),
            conf);

    // Initialize staging/output directory
    Path writerStagingDir = this.writerAttemptIdOptional.isPresent() ? WriterUtils
        .getWriterStagingDir(properties, this.numBranches, this.branchId, this.writerAttemptIdOptional.get())
        : WriterUtils.getWriterStagingDir(properties, this.numBranches, this.branchId);
    this.stagingFile = new Path(writerStagingDir, this.fileName);

    this.outputFile =
        new Path(WriterUtils.getWriterOutputDir(properties, this.numBranches, this.branchId), this.fileName);
    this.allOutputFilesPropName = ForkOperatorUtils
        .getPropertyNameForBranch(ConfigurationKeys.WRITER_FINAL_OUTPUT_FILE_PATHS, this.numBranches, this.branchId);

    // Deleting the staging file if it already exists, which can happen if the
    // task failed and the staging file didn't get cleaned up for some reason.
    // Deleting the staging file prevents the task retry from being blocked.
    if (this.fs.exists(this.stagingFile)) {
      LOG.warn(String.format("Task staging file %s already exists, deleting it", this.stagingFile));
      HadoopUtils.deletePath(this.fs, this.stagingFile, false);
    }

    this.shouldIncludeRecordCountInFileName = properties.getPropAsBoolean(ForkOperatorUtils
        .getPropertyNameForBranch(WRITER_INCLUDE_RECORD_COUNT_IN_FILE_NAMES, this.numBranches, this.branchId), false);

    this.bufferSize = properties.getPropAsInt(ForkOperatorUtils
            .getPropertyNameForBranch(ConfigurationKeys.WRITER_BUFFER_SIZE, this.numBranches, this.branchId),
        ConfigurationKeys.DEFAULT_BUFFER_SIZE);

    this.replicationFactor = properties.getPropAsShort(ForkOperatorUtils
        .getPropertyNameForBranch(ConfigurationKeys.WRITER_FILE_REPLICATION_FACTOR, this.numBranches, this.branchId),
        this.fs.getDefaultReplication(this.outputFile));

    this.blockSize = properties.getPropAsLong(ForkOperatorUtils
            .getPropertyNameForBranch(ConfigurationKeys.WRITER_FILE_BLOCK_SIZE, this.numBranches, this.branchId),
        this.fs.getDefaultBlockSize(this.outputFile));

    this.filePermission = HadoopUtils.deserializeWriterFilePermissions(properties, this.numBranches, this.branchId);

    this.dirPermission = HadoopUtils.deserializeWriterDirPermissions(properties, this.numBranches, this.branchId);

    this.group = Optional.fromNullable(properties.getProp(ForkOperatorUtils
        .getPropertyNameForBranch(ConfigurationKeys.WRITER_GROUP_NAME, this.numBranches, this.branchId)));

    // Create the parent directory of the output file if it does not exist
    boolean shouldRetry = properties.getPropAsBoolean(WRITER_RETRY_ENABLED, false);
    Config retrierConfig;
    if (shouldRetry) {
      retrierConfig = ConfigBuilder.create()
          .loadProps(properties.getProperties(), WRITER_RETRY_PREFIX)
          .build()
          .withFallback(WRITER_RETRY_DEFAULTS);
      LOG.info("Retry enabled for writer with config : "+ retrierConfig.root().render(ConfigRenderOptions.concise()));

    }else {
      LOG.info("Retry disabled for writer.");
      retrierConfig = WriterUtils.NO_RETRY_CONFIG;
    }
    WriterUtils.mkdirsWithRecursivePermissionWithRetry(this.fs, this.outputFile.getParent(), this.dirPermission, retrierConfig);
    this.bytesWritten = Optional.absent();

    this.defaultMetadata = new GlobalMetadata();
    for (StreamCodec c : getEncoders()) {
      this.defaultMetadata.addTransferEncoding(c.getTag());
    }

    this.partitionKey = builder.getPartitionPath(properties);
    if (builder.getPartitionPath(properties) != null) {
      properties.setProp(ConfigurationKeys.WRITER_PARTITION_PATH_KEY + "_" + builder.getWriterId(), partitionKey);
    }
  }

  @Override
  public Descriptor getDataDescriptor() {
    // Dataset is resulted from WriterUtils.getWriterOutputDir(properties, this.numBranches, this.branchId)
    // The writer dataset might not be same as the published dataset
    DatasetDescriptor datasetDescriptor =
        new DatasetDescriptor(fs.getScheme(), fs.getUri(), outputFile.getParent().toString());

    if (partitionKey == null) {
      return datasetDescriptor;
    }

    return new PartitionDescriptor(partitionKey, datasetDescriptor);
  }

  /**
   * Create the staging output file and an {@link OutputStream} to write to the file.
   *
   * @return an {@link OutputStream} to write to the staging file
   * @throws IOException if it fails to create the file and the {@link OutputStream}
   */
  protected OutputStream createStagingFileOutputStream()
      throws IOException {
    OutputStream out = this.fs
        .create(this.stagingFile, this.filePermission, true, this.bufferSize, this.replicationFactor, this.blockSize,
            null);

    // encoders need to be attached to the stream in reverse order since we should write to the
    // innermost encoder first
    for (StreamCodec encoder : Lists.reverse(getEncoders())) {
      out = encoder.encodeOutputStream(out);
    }

    return this.closer.register(out);
  }

  /**
   * Set the group name of the staging output file.
   *
   * @throws IOException if it fails to set the group name
   */
  protected void setStagingFileGroup()
      throws IOException {
    Preconditions.checkArgument(this.fs.exists(this.stagingFile),
        String.format("Staging output file %s does not exist", this.stagingFile));
    if (this.group.isPresent()) {
      HadoopUtils.setGroup(this.fs, this.stagingFile, this.group.get());
    }
  }

  protected List<StreamCodec> getEncoders() {
    return encoders;
  }

  public GlobalMetadata getDefaultMetadata() {
    return defaultMetadata;
  }

  @Override
  public long bytesWritten()
      throws IOException {
    if (this.bytesWritten.isPresent()) {
      return this.bytesWritten.get().longValue();
    }
    return 0l;
  }

  /**
   * {@inheritDoc}.
   *
   * <p>
   *   This default implementation simply renames the staging file to the output file. If the output file
   *   already exists, it will delete it first before doing the renaming.
   * </p>
   *
   * @throws IOException if any file operation fails
   */
  @Override
  public void commit()
      throws IOException {
    this.closer.close();

    setStagingFileGroup();

    if (!this.fs.exists(this.stagingFile)) {
      throw new IOException(String.format("File %s does not exist", this.stagingFile));
    }

    FileStatus stagingFileStatus = this.fs.getFileStatus(this.stagingFile);

    // Double check permission of staging file
    if (!stagingFileStatus.getPermission().equals(this.filePermission)) {
      this.fs.setPermission(this.stagingFile, this.filePermission);
    }

    this.bytesWritten = Optional.of(Long.valueOf(stagingFileStatus.getLen()));

    LOG.info(String.format("Moving data from %s to %s", this.stagingFile, this.outputFile));
    // For the same reason as deleting the staging file if it already exists, overwrite
    // the output file if it already exists to prevent task retry from being blocked.
    HadoopUtils.renamePath(this.fs, this.stagingFile, this.outputFile, true);

    // The staging file is moved to the output path in commit, so rename to add record count after that
    if (this.shouldIncludeRecordCountInFileName) {
      String filePathWithRecordCount = addRecordCountToFileName();
      this.properties.appendToSetProp(this.allOutputFilesPropName, filePathWithRecordCount);
    } else {
      this.properties.appendToSetProp(this.allOutputFilesPropName, getOutputFilePath());
    }

    FsWriterMetrics metrics = new FsWriterMetrics(
        this.id,
        new PartitionIdentifier(this.partitionKey, this.branchId),
        ImmutableSet.of(new FsWriterMetrics.FileInfo(this.outputFile.getName(), recordsWritten()))
    );
    this.properties.setProp(FS_WRITER_METRICS_KEY, metrics.toJson());
 }

  /**
   * {@inheritDoc}.
   *
   * <p>
   *   This default implementation simply deletes the staging file if it exists.
   * </p>
   *
   * @throws IOException if deletion of the staging file fails
   */
  @Override
  public void cleanup()
      throws IOException {
    // Delete the staging file
    if (this.fs.exists(this.stagingFile)) {
      HadoopUtils.deletePath(this.fs, this.stagingFile, false);
    }
  }

  @Override
  public void close()
      throws IOException {
    this.closer.close();
  }

  private synchronized String addRecordCountToFileName()
      throws IOException {
    String filePath = getOutputFilePath();
    String filePathWithRecordCount = IngestionRecordCountProvider.constructFilePath(filePath, recordsWritten());
    LOG.info("Renaming " + filePath + " to " + filePathWithRecordCount);
    HadoopUtils.renamePath(this.fs, new Path(filePath), new Path(filePathWithRecordCount), true);
    this.outputFile = new Path(filePathWithRecordCount);
    return filePathWithRecordCount;
  }

  @Override
  public State getFinalState() {
    State state = new State();

    try {
      state.setProp("RecordsWritten", recordsWritten());
    } catch (Exception exception) {
      // If Writer fails to return recordsWritten, it might not be implemented, or implemented incorrectly.
      // Omit property instead of failing.
      LOG.warn("Failed to get final state recordsWritten", exception);
    }

    try {
      state.setProp("BytesWritten", bytesWritten());
    } catch (Exception exception) {
      // If Writer fails to return bytesWritten, it might not be implemented, or implemented incorrectly.
      // Omit property instead of failing.
      LOG.warn("Failed to get final state bytesWritten", exception);
    }

    return state;
  }

  /**
   * Get the output file path.
   *
   * @return the output file path
   */
  public String getOutputFilePath() {
    return this.outputFile.toString();
  }

  /**
   * Get the fully-qualified output file path.
   *
   * @return the fully-qualified output file path
   */
  public String getFullyQualifiedOutputFilePath() {
    return this.fs.makeQualified(this.outputFile).toString();
  }

  /**
   * Classes that extends this method needs to determine if writerAttemptIdOptional is present and to avoid
   * problems of overriding, adding another checking on class type.
   */
  @Override
  public boolean isSpeculativeAttemptSafe() {
    return this.writerAttemptIdOptional.isPresent() && this.getClass() == FsDataWriter.class;
  }
}
