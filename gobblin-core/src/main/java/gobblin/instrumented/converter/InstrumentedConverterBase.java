/*
 * (c) 2014 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.instrumented.converter;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.io.Closer;

import gobblin.configuration.WorkUnitState;
import gobblin.converter.Converter;
import gobblin.converter.DataConversionException;
import gobblin.instrumented.Instrumentable;
import gobblin.instrumented.Instrumented;
import gobblin.metrics.GobblinMetrics;
import gobblin.metrics.MetricContext;
import gobblin.metrics.MetricNames;


/**
 * package-private implementation of instrumentation for {@link gobblin.converter.Converter}.
 * See {@link gobblin.instrumented.converter.InstrumentedConverter} for extensible class.
 */
abstract class InstrumentedConverterBase<SI, SO, DI, DO> extends Converter<SI, SO, DI, DO>
    implements Instrumentable, Closeable {

  private boolean instrumentationEnabled = false;
  protected MetricContext metricContext = new MetricContext.Builder(InstrumentedConverterBase.class.getName()).build();
  protected Optional<Meter> recordsInMeter = Optional.absent();
  protected Optional<Meter> recordsOutMeter = Optional.absent();
  protected Optional<Meter> recordsExceptionMeter = Optional.absent();
  protected Optional<Timer> converterTimer = Optional.absent();
  protected final Closer closer = Closer.create();

  @Override
  public Converter<SI, SO, DI, DO> init(WorkUnitState workUnit) {
    Converter<SI, SO, DI, DO> converter = super.init(workUnit);

    this.instrumentationEnabled = GobblinMetrics.isEnabled(workUnit);

    if (isInstrumentationEnabled()) {
      this.metricContext = closer.register(Instrumented.getMetricContext(workUnit, this.getClass()));

      this.recordsInMeter = Optional.of(this.metricContext.meter(MetricNames.ConverterMetrics.RECORDS_IN_METER));
      this.recordsOutMeter = Optional.of(this.metricContext.meter(MetricNames.ConverterMetrics.RECORDS_OUT_METER));
      this.recordsExceptionMeter = Optional.of(
          this.metricContext.meter(MetricNames.ConverterMetrics.RECORDS_FAILED_METER));
      this.converterTimer = Optional.of(this.metricContext.timer(MetricNames.ConverterMetrics.CONVERT_TIMER));
    }

    return converter;
  }

  @Override
  public boolean isInstrumentationEnabled() {
    return this.instrumentationEnabled;
  }

  @Override
  public Iterable<DO> convertRecord(SO outputSchema, DI inputRecord, WorkUnitState workUnit)
      throws DataConversionException {

    if(!isInstrumentationEnabled()) {
      return convertRecordImpl(outputSchema, inputRecord, workUnit);
    }

    try {
      long startTime = System.nanoTime();

      beforeConvert(outputSchema, inputRecord, workUnit);
      final Iterable<DO> it = convertRecordImpl(outputSchema, inputRecord, workUnit);
      afterConvert(it, startTime);

      return Iterables.transform(it, new Function<DO, DO>() {
        @Override
        public DO apply(DO input) {
          onIterableNext(input);
          return input;
        }
      });
    } catch(DataConversionException exception) {
      onException(exception);
      throw exception;
    }
  }

  /**
   * Called before conversion.
   * @param outputSchema
   * @param inputRecord
   * @param workUnit
   */
  public void beforeConvert(SO outputSchema, DI inputRecord, WorkUnitState workUnit) {
    Instrumented.markMeter(this.recordsInMeter);
  }

  /**
   * Called after conversion.
   * @param iterable conversion result.
   * @param startTimeNanos start time of conversion.
   */
  public void afterConvert(Iterable<DO> iterable, long startTimeNanos) {
    Instrumented.updateTimer(this.converterTimer, System.nanoTime() - startTimeNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Called every time next() method in iterable is called.
   * @param next next value in iterable.
   */
  public void onIterableNext(DO next) {
    Instrumented.markMeter(this.recordsOutMeter);
  }

  /**
   * Called when converter throws an exception.
   * @param exception exception thrown.
   */
  public void onException(Exception exception) {
    if(DataConversionException.class.isInstance(exception)) {
      Instrumented.markMeter(this.recordsExceptionMeter);
    }
  }

  /**
   * Subclasses should implement this method instead of convertRecord.
   *
   * See {@link gobblin.converter.Converter#convertRecord}.
   */
  public abstract Iterable<DO> convertRecordImpl(SO outputSchema, DI inputRecord, WorkUnitState workUnit)
      throws DataConversionException;

  @Override
  public void close()
      throws IOException {
    this.closer.close();
  }

  @Override
  public MetricContext getMetricContext() {
    return this.metricContext;
  }

}
