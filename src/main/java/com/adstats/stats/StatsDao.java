package com.adstats.stats;

import com.adstats.core.dao.AbstractDao;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static com.datastax.driver.core.querybuilder.QueryBuilder.gte;
import static com.datastax.driver.core.querybuilder.QueryBuilder.in;
import static com.datastax.driver.core.querybuilder.QueryBuilder.incr;
import static com.datastax.driver.core.querybuilder.QueryBuilder.lte;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class StatsDao extends AbstractDao {
  private static final List<String> METRIC_NAMES = Stream.of(Metric.values()).map(Metric::getKey).collect(toList());

  private final PreparedStatement increaseMetricQuery;
  private final PreparedStatement getMetricsQuery;

  public StatsDao(Session cassandraSession) {
    super(cassandraSession);

    increaseMetricQuery = cassandraSession.prepare(QueryBuilder
        .update("stats")
        .with(incr("counter_value"))
        .where(eq("metric_name", bindMarker()))
        .and(eq("event_time", bindMarker()))
        .and(eq("browser", bindMarker()))
        .and(eq("os", bindMarker()))
    );

    getMetricsQuery = cassandraSession.prepare(QueryBuilder
        .select("metric_name", "browser", "os", "counter_value")
        .from("stats")
        .where(in("metric_name", bindMarker()))
        .and(gte("event_time", bindMarker()))
        .and(lte("event_time", bindMarker())));
  }

  void updateMetric(Metric metric, Instant time, String browser, String os) {
    BoundStatement statement = increaseMetricQuery.bind(metric.toString().toLowerCase(), time, browser, os);
    getCassandraSession().execute(statement);
  }

  Stats getStatistics(Interval interval) {
    Map<String, Long> metricsMap = fetchMetricsForInterval(interval).stream()
        .collect(
            groupingBy(row -> row.getString("metric_name"),
                summingLong(row -> row.getLong("counter_value"))));
    return getStatsFromMap(metricsMap);
  }

  List<GroupStatsItem> getStatisticsByGroup(Interval interval, List<String> groups) {
    if (Objects.isNull(groups) || groups.isEmpty()) {
      throw new IllegalArgumentException("groups should not be empty");
    }
    Set<String> groupSet = new HashSet<>(groups);
    if (groupSet.contains(GroupByField.BROWSER.getKey()) && groupSet.contains(GroupByField.OS.getKey())) {
      return getStatsForAllGroups(interval);
    } else if (groupSet.contains(GroupByField.BROWSER.getKey())) {
      return getStatsForSingleGroup(interval, GroupByField.BROWSER);
    } else {
      return getStatsForSingleGroup(interval, GroupByField.OS);
    }
  }

  private List<GroupStatsItem> getStatsForAllGroups(Interval interval) {
    List<GroupStatsItem> groupStatsItems = new ArrayList<>();
    Map<String, Map<String, Map<String, Long>>> metricsByGroupsMap = fetchMetricsForInterval(interval).stream()
        .collect(
            groupingBy(row -> row.getString(GroupByField.BROWSER.getKey()),
                groupingBy(row -> row.getString(GroupByField.OS.getKey()),
                    groupingBy(row -> row.getString("metric_name"),
                        summingLong(row -> row.getLong("counter_value"))))));


    metricsByGroupsMap.forEach((browserName, subGroup) -> subGroup.forEach((osName, metricsMap) -> {
      GroupStatsItem statsItem = new GroupStatsItem();
      statsItem.addField(GroupByField.BROWSER.getKey(), browserName);
      statsItem.addField(GroupByField.OS.getKey(), osName);
      statsItem.setStats(getStatsFromMap(metricsMap));
      groupStatsItems.add(statsItem);
    }));

    return groupStatsItems;
  }

  private List<GroupStatsItem> getStatsForSingleGroup(Interval interval, GroupByField groupByField) {
    List<GroupStatsItem> groupStatsItems = new ArrayList<>();
    Map<String, Map<String, Long>> metricsByGroupsMap = fetchMetricsForInterval(interval).stream()
        .collect(
            groupingBy(row -> row.getString(groupByField.getKey()),
                groupingBy(row -> row.getString("metric_name"),
                    summingLong(row -> row.getLong("counter_value")))));

    metricsByGroupsMap.forEach((groupValue, metricsMap) -> {
        GroupStatsItem statsItem = new GroupStatsItem();
        statsItem.addField(groupByField.getKey(), groupValue);
        statsItem.setStats(getStatsFromMap(metricsMap));
        groupStatsItems.add(statsItem);
    });

    return groupStatsItems;
  }

  private List<Row> fetchMetricsForInterval(Interval interval) {
    return getCassandraSession()
        .execute(getMetricsQuery.bind(METRIC_NAMES, interval.getStart(), interval.getEnd()))
        .all();
  }

  private static Stats getStatsFromMap(Map<String, Long> metricsMap) {
    long deliveries = metricsMap.getOrDefault(Metric.DELIVERY.getKey(), 0L);
    long clicks = metricsMap.getOrDefault(Metric.CLICK.getKey(), 0L);
    long installs = metricsMap.getOrDefault(Metric.INSTALL.getKey(), 0L);
    return new Stats(deliveries, clicks, installs);
  }
}
