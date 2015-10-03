/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.cluster.callback.reporting;

import com.eucalyptus.bootstrap.Bootstrap;
import com.eucalyptus.cloudwatch.common.CloudWatch;
import com.eucalyptus.cloudwatch.common.internal.domain.metricdata.Units;
import com.eucalyptus.cloudwatch.common.msgs.Dimension;
import com.eucalyptus.cloudwatch.common.msgs.Dimensions;
import com.eucalyptus.cloudwatch.common.msgs.MetricData;
import com.eucalyptus.cloudwatch.common.msgs.MetricDatum;
import com.eucalyptus.cloudwatch.common.msgs.PutMetricDataResponseType;
import com.eucalyptus.cloudwatch.common.msgs.PutMetricDataType;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.Topology;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.util.CollectionUtils;
import com.eucalyptus.util.async.AsyncRequests;
import com.eucalyptus.util.async.CheckedListenableFuture;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import edu.ucsb.eucalyptus.msgs.BaseMessage;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Created by ethomas on 6/16/15.
 */
public class AbsoluteMetricQueue {

  public static volatile Integer ABSOLUTE_METRIC_NUM_DB_OPERATIONS_PER_TRANSACTION = 10000;
  public static volatile Integer ABSOLUTE_METRIC_NUM_DB_OPERATIONS_UNTIL_SESSION_FLUSH = 50;

  static boolean useScanningConverter = true;

  static {
    ScheduledExecutorService dbCleanupService = Executors
      .newSingleThreadScheduledExecutor();
    dbCleanupService.scheduleAtFixedRate(new DBCleanupService(), 1, 30,
      TimeUnit.MINUTES);
  }


  private static final Logger LOG = Logger.getLogger(AbsoluteMetricQueue.class);
  private static final LinkedBlockingQueue<AbsoluteMetricQueueItem> dataQueue = new LinkedBlockingQueue<>( );

  private static final ScheduledExecutorService dataFlushTimer = Executors
    .newSingleThreadScheduledExecutor();

  private static AbsoluteMetricQueue singleton = getInstance();

  public static AbsoluteMetricQueue getInstance() {
    synchronized (AbsoluteMetricQueue.class) {
      if (singleton == null)
        singleton = new AbsoluteMetricQueue();
    }
    return singleton;
  }

  private static Runnable safeRunner = new Runnable( ) {
    @Override
    public void run() {
      if ( !Bootstrap.isOperational( ) ) return;
      long before = System.currentTimeMillis();
      try {
        List<AbsoluteMetricQueueItem> dataBatch = Lists.newArrayList();
        dataQueue.drainTo(dataBatch);
        LOG.trace( "Cluster:Timing:dataBatch.size()=" + dataBatch.size( ) );
        final Predicate<AbsoluteMetricQueueItem> expired =
            AbsoluteMetricQueueItem.createdBefore( System.currentTimeMillis( ) - TimeUnit.MINUTES.toMillis( 5 ) );
        int expiredQueueItems = CollectionUtils.reduce( dataBatch, 0, CollectionUtils.count( expired ) );
        if ( expiredQueueItems > 0 ) {
          LOG.error( "Dropping " + expiredQueueItems + " expired items from system metrics queue" );
        }
        LOG.trace( "Cluster:Timing:dataBatch.size()=" + dataBatch.size( ) );
        long t1 = System.currentTimeMillis();
        if ( useScanningConverter ) {
          dataBatch = FullTableScanAbsoluteMetricConverter.dealWithAbsoluteMetrics(
              Iterables.filter( dataBatch, Predicates.not( expired ) ) );
        } else {
          dataBatch = DefaultAbsoluteMetricConverter.dealWithAbsoluteMetrics(
              Iterables.filter( dataBatch, Predicates.not( expired ) ) );
        }
        long t2 = System.currentTimeMillis();
        LOG.trace( "Cluster:Timing:dataBatch.dealWithAbsoluteMetrics():time=" + ( t2 - t1 ) );
        dataBatch = foldMetrics(dataBatch);
        long t3 = System.currentTimeMillis();
        LOG.trace( "Cluster:Timing:dataBatch.foldMetrics():time=" + ( t3 - t2 ) );
        List<PutMetricDataType> putMetricDataTypeList =convertToPutMetricDataList(dataBatch);
        long t4 = System.currentTimeMillis();
        LOG.trace( "Cluster:Timing:dataBatch.convertToPutMetricDataList():time=" + ( t4 - t3 ) );
        putMetricDataTypeList = CloudWatchHelper.consolidatePutMetricDataList(putMetricDataTypeList);
        long t5 = System.currentTimeMillis();
        LOG.trace( "Cluster:Timing:dataBatch.consolidatePutMetricDataList():time=" + ( t5 - t4 ) );
        callPutMetricData(putMetricDataTypeList);
        long t6 = System.currentTimeMillis();
        LOG.trace( "Cluster:Timing:ListMetricManager.callPutMetricData():time=" + ( t6 - t5 ) );
      } catch (Throwable ex) {
        LOG.error(ex,ex);
      } finally {
        long after = System.currentTimeMillis();
        LOG.trace( "Cluster:Timing:time=" + ( after - before ) );
      }
    }
  };

  private static List<PutMetricDataType> convertToPutMetricDataList(List<AbsoluteMetricQueueItem> dataBatch) {
    final List<PutMetricDataType> putMetricDataTypeList = Lists.newArrayList();
    for (AbsoluteMetricQueueItem item: dataBatch) {
      PutMetricDataType putMetricDataType = new PutMetricDataType();
      //noinspection deprecation
      putMetricDataType.setUserId(item.getAccountId());
      putMetricDataType.markPrivileged();
      putMetricDataType.setNamespace(item.getNamespace());
      MetricData metricData = new MetricData();
      ArrayList<MetricDatum> member = Lists.newArrayList(item.getMetricDatum());
      metricData.setMember(member);
      putMetricDataType.setMetricData(metricData);
      putMetricDataTypeList.add(putMetricDataType);
    }
    return putMetricDataTypeList;
  }

  private static void callPutMetricData( final List<PutMetricDataType> putMetricDataList ) throws Exception {
    final List<ServiceConfiguration> serviceConfigurations =
        Lists.newArrayList( Topology.lookupMany( CloudWatch.class ) );
    final Map<ServiceConfiguration, Semaphore> semaphoreMap = Maps.newHashMap( );
    for ( final ServiceConfiguration serviceConfiguration: serviceConfigurations ) {
      semaphoreMap.put( serviceConfiguration, new Semaphore( 8 ) );
    }
    final Iterator<ServiceConfiguration> putToServiceConfiguration =
        Iterables.cycle( serviceConfigurations ).iterator( );
    for ( final PutMetricDataType putMetricData : putMetricDataList ) {
      final ServiceConfiguration serviceConfiguration = putToServiceConfiguration.next( );
      final Semaphore activePuts = semaphoreMap.get( serviceConfiguration );
      activePuts.acquire( );
      try {
        final CheckedListenableFuture<BaseMessage> replyFuture =
            AsyncRequests.dispatch( serviceConfiguration, putMetricData );
        replyFuture.addListener( new Runnable( ) {
          @Override
          public void run( ) {
            try {
              final BaseMessage reply = replyFuture.get( );
              if ( !( reply instanceof PutMetricDataResponseType ) ) {
                LOG.error( "Error putting compute system metric data" );
              }
            } catch ( ExecutionException | InterruptedException e ) {
              LOG.error( "Error putting compute system metric data", e );
            } finally {
              activePuts.release( );
            }
          }
        } );
      } catch ( Throwable t ) {
        activePuts.release( );
        LOG.error( "Error putting compute system metric data", t );
      }
    }
  }

  private static List<AbsoluteMetricQueueItem> foldMetrics(List<AbsoluteMetricQueueItem> dataBatch) {
    final List<AbsoluteMetricQueueItem> foldedMetrics = Lists.newArrayList();
    if (dataBatch != null) {
      for (AbsoluteMetricQueueItem queueItem : dataBatch) {
        // keep the same metric data unless the namespace is AWS/EC2.  In that case points will exist with dimensions
        // instance-id, image-id, instance-type, and (optionally) autoscaling group name.  These points have 4
        // dimensions, and we are really only supposed to have one dimension (or zero) for aggregation purposes.
        if (queueItem != null && queueItem.getNamespace() != null && "AWS/EC2".equals(queueItem.getNamespace())) {
          MetricDatum metricDatum = queueItem.getMetricDatum();
          if (metricDatum != null && metricDatum.getDimensions() != null &&
            metricDatum.getDimensions().getMember() != null) {
            Set<Dimension> dimensionSet = Sets.newLinkedHashSet(metricDatum.getDimensions().getMember());
            for (Set<Dimension> permutation: Sets.powerSet(dimensionSet)) {
              if (permutation.size() > 1) continue;
              MetricDatum newMetricDatum = new MetricDatum();
              newMetricDatum.setValue(metricDatum.getValue());
              newMetricDatum.setUnit(metricDatum.getUnit());
              newMetricDatum.setStatisticValues(metricDatum.getStatisticValues());
              newMetricDatum.setTimestamp(metricDatum.getTimestamp());
              newMetricDatum.setMetricName(metricDatum.getMetricName());
              ArrayList<Dimension> newDimensionsList = Lists.newArrayList(permutation);
              Dimensions newDimensions = new Dimensions();
              newDimensions.setMember(newDimensionsList);
              newMetricDatum.setDimensions(newDimensions);
              AbsoluteMetricQueueItem newQueueItem = new AbsoluteMetricQueueItem();
              newQueueItem.setAccountId(queueItem.getAccountId());
              newQueueItem.setNamespace(queueItem.getNamespace());
              newQueueItem.setMetricDatum(newMetricDatum);
              foldedMetrics.add(newQueueItem);
            }
          } else {
            foldedMetrics.add(queueItem);
          }
        } else {
          foldedMetrics.add(queueItem);
        }
      }
    }
    return foldedMetrics;
  }

  static {
    dataFlushTimer.scheduleAtFixedRate(safeRunner, 0, 1, TimeUnit.MINUTES);
  }

  private void scrub(AbsoluteMetricQueueItem absoluteMetricQueueItem, Date now) {
    MetricDatum datum = absoluteMetricQueueItem.getMetricDatum();
    if (datum.getUnit() == null || datum.getUnit().trim().isEmpty()) datum.setUnit(Units.None.toString());
    if (datum.getTimestamp() == null) datum.setTimestamp(now);
  }

  public void addQueueItems(List<AbsoluteMetricQueueItem> queueItems) {
    Date now = new Date();

    for (final AbsoluteMetricQueueItem queueItem : queueItems) {
      scrub(queueItem, now);
      dataQueue.offer(queueItem);
    }
  }

  private static class DBCleanupService implements Runnable {
    @Override
    public void run() {
      LOG.info("Calling absolute metric history (cloud) db cleanup service");
      if (!( Bootstrap.isOperational() &&
        Topology.isEnabled(Eucalyptus.class) )) {
        LOG.info("Eucalyptus service is not ENABLED");
        return;
      }

      Date thirtyMinutesAgo = new Date(System.currentTimeMillis() - 30 * 60 * 1000L);
      try {
        AbsoluteMetricHelper.deleteAbsoluteMetricHistory(thirtyMinutesAgo);
      } catch (Exception ex) {
        LOG.error(ex);
        LOG.error(ex, ex);
      }
      LOG.info("Done cleaning up absolute metric history (cloud) db");
    }
  }

}
