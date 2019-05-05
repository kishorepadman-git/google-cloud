/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.cdap.plugin.gcp.bigquery.sink;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.Table;
import com.google.cloud.hadoop.io.bigquery.BigQueryFileFormat;
import com.google.cloud.hadoop.io.bigquery.output.BigQueryOutputConfiguration;
import com.google.cloud.hadoop.io.bigquery.output.BigQueryTableFieldSchema;
import com.google.cloud.hadoop.io.bigquery.output.BigQueryTableSchema;
import com.google.gson.stream.JsonWriter;
import io.cdap.cdap.api.common.Bytes;
import io.cdap.cdap.api.data.batch.Output;
import io.cdap.cdap.api.data.batch.OutputFormatProvider;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.batch.BatchSinkContext;
import io.cdap.plugin.common.LineageRecorder;
import io.cdap.plugin.gcp.bigquery.util.BigQueryUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Base class for Big Query batch sink plugins.
 */
public abstract class AbstractBigQuerySink extends BatchSink<StructuredRecord, Text, NullWritable> {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractBigQuerySink.class);

  private static final String gcsPathFormat = "gs://%s";
  private static final String temporaryBucketFormat = gcsPathFormat + "/input/%s-%s";
  private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
  private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS");

  // UUID for the run. Will be used as bucket name if bucket is not provided.
  // UUID is used since GCS bucket names must be globally unique.
  private final UUID uuid = UUID.randomUUID();
  private Configuration baseConfiguration;

  /**
   * Executes main prepare run logic. Child classes cannot override this method,
   * instead they should implement two methods {@link #prepareRunValidation(BatchSinkContext)}
   * and {@link #prepareRunInternal(BatchSinkContext, String)} in order to add custom logic.
   *
   * @param context batch sink context
   */
  @Override
  public final void prepareRun(BatchSinkContext context) throws Exception {
    prepareRunValidation(context);

    createDataset(context.isPreviewEnabled());

    baseConfiguration = BigQueryUtil.getBigQueryConfig(getConfig().getServiceAccountFilePath(),
                                                       getConfig().getProject());
    String bucket = configureBucket();

    prepareRunInternal(context, bucket);
  }

  @Override
  public final void onRunFinish(boolean succeeded, BatchSinkContext context) {
    if (getConfig().getBucket() == null) {
      Path gcsPath = new Path(String.format(gcsPathFormat, uuid.toString()));
      try {
        FileSystem fs = gcsPath.getFileSystem(baseConfiguration);
        if (fs.exists(gcsPath)) {
          fs.delete(gcsPath, true);
          LOG.debug("Deleted temporary bucket '{}'", gcsPath);
        }
      } catch (IOException e) {
        LOG.warn("Failed to delete bucket '{}': {}", gcsPath, e.getMessage());
      }
    }
  }

  /**
   * Initializes output along with lineage recording for given table and its schema.
   *
   * @param context batch sink context
   * @param outputName output name
   * @param tableName table name
   * @param tableSchema table schema
   * @param bucket bucket name
   */
  protected final void initOutput(BatchSinkContext context, String outputName,
                                  String tableName, Schema tableSchema, String bucket) throws IOException {
    LOG.debug("Init output for table '{}' with schema: {}", tableName, tableSchema);
    validateSchema(tableName, tableSchema);
    List<BigQueryTableFieldSchema> fields = getBigQueryTableFields(tableSchema);
    Configuration configuration = getOutputConfiguration(bucket, tableName, fields);

    // Both emitLineage and setOutputFormat internally try to create an external dataset if it does not already exist.
    // We call emitLineage before since it creates the dataset with schema which is used.
    List<String> fieldNames = fields.stream()
      .map(BigQueryTableFieldSchema::getName)
      .collect(Collectors.toList());
    recordLineage(context, outputName, tableSchema, fieldNames);
    context.addOutput(Output.of(outputName, getOutputFormatProvider(configuration, tableName, tableSchema)));
  }

  /**
   * Decodes object and writes to json writer.
   * @param writer json writer to write the object to
   * @param name name of the field to be decoded
   * @param object object to be decoded
   * @param fieldSchema field schema to be decoded
   */
  protected void decode(JsonWriter writer, @Nullable String name, Object object,
                        Schema fieldSchema) throws IOException {
    Schema schema = BigQueryUtil.getNonNullableSchema(fieldSchema);
    switch (schema.getType()) {
      case NULL:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
      case BOOLEAN:
      case STRING:
      case BYTES:
        decodeSimpleTypes(writer, name, object, schema);
        break;
      case ARRAY:
        decodeArray(writer, name, object, schema);
        break;
      case RECORD:
        decodeRecord(writer, name, object, schema);
        break;
      default:
        throw new IllegalStateException(
          String.format("Field '%s' is of unsupported type '%s'", name, fieldSchema.getType()));
    }
  }

  private void decodeSimpleTypes(JsonWriter writer, @Nullable String name, Object object,
                                 Schema schema) throws IOException {
    if (name != null) {
      writer.name(name);
    }

    if (object == null) {
      writer.nullValue();
      return;
    }

    Schema.LogicalType logicalType = schema.getLogicalType();
    if (logicalType != null) {
      switch (logicalType) {
        case DATE:
          writer.value(Objects.requireNonNull(LocalDate.ofEpochDay(((Integer) object).longValue()).toString()));
          break;
        case TIME_MILLIS:
          writer.value(timeFormatter.format(
            Objects.requireNonNull(LocalTime.ofNanoOfDay(TimeUnit.MILLISECONDS.toNanos(((Integer) object))))));
          break;
        case TIME_MICROS:
          writer.value(timeFormatter.format(
            Objects.requireNonNull(LocalTime.ofNanoOfDay(TimeUnit.MICROSECONDS.toNanos((Long) object)))));
          break;
        case TIMESTAMP_MILLIS:
          //timestamp for json input should be in this format yyyy-MM-dd HH:mm:ss.SSSSSS
          writer.value(dateTimeFormatter.format(
            Objects.requireNonNull(getZonedDateTime((long) object, TimeUnit.MILLISECONDS))));
          break;
        case TIMESTAMP_MICROS:
          writer.value(dateTimeFormatter.format(
            Objects.requireNonNull(getZonedDateTime((long) object, TimeUnit.MICROSECONDS))));
          break;
        default:
          throw new IllegalStateException(
            String.format("Field '%s' is of unsupported type '%s'", name, logicalType.getToken()));
      }
      return;
    }

    switch (schema.getType()) {
      case NULL:
        writer.nullValue(); // nothing much to do here.
        break;
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
        writer.value((Number) object);
        break;
      case BOOLEAN:
        writer.value((Boolean) object);
        break;
      case STRING:
        writer.value(object.toString());
        break;
      case BYTES:
        if (object instanceof ByteBuffer) {
          writer.value(Base64.getUrlEncoder().encodeToString(Bytes.getBytes((ByteBuffer) object)));
        } else if (object.getClass().isArray() && object.getClass().getComponentType().equals(byte.class)) {
          writer.value(Base64.getUrlEncoder().encodeToString((byte[]) object));
        } else {
          throw new IOException("Expects either ByteBuffer or byte[]. Got " + object.getClass());
        }
        break;
      default:
        throw new IllegalStateException(String.format("Field '%s' is of unsupported type '%s'",
                                                      name, schema.getType()));
    }
  }

  private void decodeArray(JsonWriter writer, String name, Object value, Schema fieldSchema) throws IOException {
    if (value == null) {
      writer.nullValue();
      return;
    }

    if (!(value instanceof Collection) && !value.getClass().isArray()) {
      throw new IllegalArgumentException("The value should be of type collection or array. Got: " + value.getClass());
    }

    Schema componentSchema = BigQueryUtil.getNonNullableSchema(fieldSchema.getComponentSchema());
    // Arrays within arrays are not allowed in big query
    if (componentSchema.getType() == Schema.Type.ARRAY) {
      throw new IllegalArgumentException("Nested Arrays is not a valid type in big query.");
    }

    writer.name(name);
    writer.beginArray();
    if (value instanceof Collection) {
      for (Object element : (Collection) value) {
        // big query does not allow null values in array items
        if (element != null) {
          decode(writer, null, element, componentSchema);
        }
      }
    } else {
      for (int i = 0; i < Array.getLength(value); i++) {
        // big query does not allow null values in array items
        if (Array.get(value, i) != null) {
          decode(writer, null, Array.get(value, i), componentSchema);
        }
      }
    }
    writer.endArray();
  }

  private void decodeRecord(JsonWriter writer, @Nullable String name, Object value,
                            Schema fieldSchema) throws IOException {
    if (!(value instanceof StructuredRecord)) {
      throw new IOException("Expects a record, but have " + value.getClass());
    }

    if (name != null) {
      writer.name(name);
    }
    StructuredRecord record = (StructuredRecord) value;
    writer.beginObject();
    for (Schema.Field field : fieldSchema.getFields()) {
      Object fieldValue = record.get(field.getName());
      decode(writer, field.getName(), fieldValue, field.getSchema());
    }
    writer.endObject();
  }

  private ZonedDateTime getZonedDateTime(long ts, TimeUnit unit) {
    long mod = unit.convert(1, TimeUnit.SECONDS);
    int fraction = (int) (ts % mod);
    long tsInSeconds = unit.toSeconds(ts);
    // create an Instant with time in seconds and fraction which will be stored as nano seconds.
    Instant instant = Instant.ofEpochSecond(tsInSeconds, unit.toNanos(fraction));
    return ZonedDateTime.ofInstant(instant, ZoneId.ofOffset("UTC", ZoneOffset.UTC));
  }

  /**
   * Child classes must provide configuration based on {@link AbstractBigQuerySinkConfig}.
   *
   * @return config instance
   */
  protected abstract AbstractBigQuerySinkConfig getConfig();

  /**
   * Child classes must override this method to provide specific validation logic to executed before
   * actual {@link #prepareRun(BatchSinkContext)} method execution.
   * For example, Batch Sink plugin can validate schema right away,
   * Batch Multi Sink does not have information at this point to do the validation.
   *
   * @param context batch sink context
   */
  protected abstract void prepareRunValidation(BatchSinkContext context);

  /**
   * Executes main prepare run logic, i.e. prepares output for given table (for Batch Sink plugin)
   * or for a number of tables (for Batch Multi Sink plugin).
   *
   * @param context batch sink context
   * @param bucket bucket name
   */
  protected abstract void prepareRunInternal(BatchSinkContext context, String bucket) throws IOException;

  /**
   * Returns output format provider instance specific to the child classes that extend this class.
   *
   * @param configuration Hadoop configuration
   * @param tableName table name
   * @param tableSchema table schema
   * @return output format provider
   */
  protected abstract OutputFormatProvider getOutputFormatProvider(Configuration configuration,
                                                                  String tableName,
                                                                  Schema tableSchema);

  /**
   * If provided dataset name does not exists in Big Query, creates it.
   * This operation only takes place, if pipeline does not run in preview mode.
   *
   * @param previewEnabled indicates whether the pipeline is running in preview.
   */
  private void createDataset(boolean previewEnabled) throws IOException {
    if (previewEnabled) {
      return;
    }

    BigQuery bigquery = BigQueryUtil.getBigQuery(getConfig().getServiceAccountFilePath(), getConfig().getProject());
    // create dataset if it does not exist
    if (bigquery.getDataset(getConfig().getDataset()) == null) {
      try {
        bigquery.create(DatasetInfo.newBuilder(getConfig().getDataset()).build());
      } catch (BigQueryException e) {
        throw new IllegalStateException("Exception occurred while creating dataset: " + getConfig().getDataset(), e);
      }
    }
  }

  /**
   * Generates full path to temporary bucket based on given bucket and table names.
   *
   * @param bucket bucket name
   * @param tableName table name
   * @return full path to temporary bucket
   */
  private String getTemporaryGcsPath(String bucket, String tableName) {
    return String.format(temporaryBucketFormat, bucket, tableName, uuid);
  }

  /**
   * Updates {@link #baseConfiguration} with bucket details.
   * Uses provided bucket, otherwise generates random.
   *
   * @return bucket name
   */
  private String configureBucket() {
    String bucket = getConfig().getBucket();
    if (bucket == null) {
      bucket = uuid.toString();
      // By default, this option is false, meaning the job can not delete the bucket.
      // So enable it only when bucket name is not provided.
      baseConfiguration.setBoolean("fs.gs.bucket.delete.enable", true);
    }
    baseConfiguration.set("fs.gs.system.bucket", bucket);
    baseConfiguration.setBoolean("fs.gs.impl.disable.cache", true);
    baseConfiguration.setBoolean("fs.gs.metadata.cache.enable", false);
    return bucket;
  }

  /**
   * Validates output schema against Big Query table schema. It throws {@link IllegalArgumentException}
   * if the output schema has more fields than Big Query table or output schema field types does not match
   * Big Query column types.
   *
   * @param tableName table name
   * @param tableSchema table schema
   */
  private void validateSchema(String tableName, Schema tableSchema) throws IOException {
    Table table = BigQueryUtil.getBigQueryTable(getConfig().getServiceAccountFilePath(),
                                                getConfig().getProject(),
                                                getConfig().getDataset(), tableName);
    if (table == null) {
      // Table does not exist, so no further validation is required.
      return;
    }

    com.google.cloud.bigquery.Schema bqSchema = table.getDefinition().getSchema();
    if (bqSchema == null) {
      // Table is created without schema, so no further validation is required.
      return;
    }

    FieldList bqFields = bqSchema.getFields();
    List<Schema.Field> outputSchemaFields = Objects.requireNonNull(tableSchema.getFields());

    // Output schema should not have fields that are not present in Big Query table.
    List<String> diff = BigQueryUtil.getSchemaMinusBqFields(outputSchemaFields, bqFields);
    if (!diff.isEmpty()) {
      throw new IllegalArgumentException(
        String.format("The output schema does not match the BigQuery table schema for '%s.%s' table. " +
                        "The table does not contain the '%s' column(s).",
                      getConfig().getDataset(), table, diff));
    }

    // validate the missing columns in output schema are nullable fields in Big Query
    List<String> remainingBQFields = BigQueryUtil.getBqFieldsMinusSchema(bqFields, outputSchemaFields);
    for (String field : remainingBQFields) {
      if (bqFields.get(field).getMode() != Field.Mode.NULLABLE) {
        throw new IllegalArgumentException(
          String.format("The output schema does not match the BigQuery table schema for '%s.%s'. " +
                          "The table requires column '%s', which is not in the output schema.",
                        getConfig().getDataset(), tableName, field));
      }
    }

    // Match output schema field type with Big Query column type
    for (Schema.Field field : tableSchema.getFields()) {
      BigQueryUtil.validateFieldSchemaMatches(bqFields.get(field.getName()),
                                              field, getConfig().getDataset(), tableName);
    }
  }

  /**
   * Generates Big Query field instances based on given CDAP table schema.
   *
   * @param tableSchema table schema
   * @return list of Big Query fields
   */
  private List<BigQueryTableFieldSchema> getBigQueryTableFields(Schema tableSchema) {
    return Objects.requireNonNull(tableSchema.getFields()).stream()
      .map(field -> new BigQueryTableFieldSchema()
        .setName(field.getName())
        .setType(getTableDataType(field.getSchema()).name())
        .setMode(getMode(field.getSchema()).name()))
      .collect(Collectors.toList());
  }

  private Field.Mode getMode(Schema schema) {
    if (BigQueryUtil.getNonNullableSchema(schema).getComponentSchema() != null) {
      return Field.Mode.REPEATED;
    } else if (schema.isNullable()) {
      return Field.Mode.NULLABLE;
    }
    return Field.Mode.REQUIRED;
  }

  /**
   * Creates Hadoop configuration for the given table and its fields.
   *
   * @param bucket bucket name
   * @param tableName table name
   * @param fields list of Big Query fields
   * @return Hadoop configuration
   */
  private Configuration getOutputConfiguration(String bucket,
                                               String tableName,
                                               List<BigQueryTableFieldSchema> fields) throws IOException {
    Configuration configuration = new Configuration(baseConfiguration);
    String temporaryGcsPath = getTemporaryGcsPath(bucket, tableName);

    BigQueryOutputConfiguration.configure(
      configuration,
      String.format("%s.%s", getConfig().getDataset(), tableName),
      new BigQueryTableSchema().setFields(fields),
      temporaryGcsPath,
      BigQueryFileFormat.NEWLINE_DELIMITED_JSON,
      TextOutputFormat.class);

    return configuration;
  }

  private void recordLineage(BatchSinkContext context,
                             String outputName,
                             Schema tableSchema,
                             List<String> fieldNames) {
    LineageRecorder lineageRecorder = new LineageRecorder(context, outputName);
    lineageRecorder.createExternalDataset(tableSchema);
    if (!fieldNames.isEmpty()) {
      lineageRecorder.recordWrite("Write", "Wrote to BigQuery table.", fieldNames);
    }
  }

  private LegacySQLTypeName getTableDataType(Schema schema) {
    schema = BigQueryUtil.getNonNullableSchema(schema);
    Schema.LogicalType logicalType = schema.getLogicalType();

    if (logicalType != null) {
      switch (logicalType) {
        case DATE:
          return LegacySQLTypeName.DATE;
        case TIME_MILLIS:
        case TIME_MICROS:
          return LegacySQLTypeName.TIME;
        case TIMESTAMP_MILLIS:
        case TIMESTAMP_MICROS:
          return LegacySQLTypeName.TIMESTAMP;
        default:
          throw new IllegalStateException("Unsupported type " + logicalType.getToken());
      }
    }

    Schema.Type type = schema.getType();
    switch (type) {
      case INT:
      case LONG:
        return LegacySQLTypeName.INTEGER;
      case STRING:
        return LegacySQLTypeName.STRING;
      case FLOAT:
      case DOUBLE:
        return LegacySQLTypeName.FLOAT;
      case BOOLEAN:
        return LegacySQLTypeName.BOOLEAN;
      case BYTES:
        return LegacySQLTypeName.BYTES;
      case ARRAY:
        return getTableDataType(schema.getComponentSchema());
      case RECORD:
        return LegacySQLTypeName.RECORD;
      default:
        throw new IllegalStateException("Unsupported type " + type);
    }
  }
}
