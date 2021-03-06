package com.linkedin.thirdeye.anomaly.monitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.thirdeye.client.DAORegistry;
import com.linkedin.thirdeye.datalayer.bao.JobManager;
import com.linkedin.thirdeye.datalayer.bao.TaskManager;

public class MonitorJobScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(MonitorJobScheduler.class);

  private ScheduledExecutorService scheduledExecutorService;

  private JobManager anomalyJobDAO;
  private TaskManager anomalyTaskDAO;
  private MonitorConfiguration monitorConfiguration;
  private MonitorJobRunner monitorJobRunner;
  private MonitorJobContext monitorJobContext;
  private static final DAORegistry DAO_REGISTRY = DAORegistry.getInstance();

  public MonitorJobScheduler(MonitorConfiguration monitorConfiguration) {
    this.anomalyJobDAO = DAO_REGISTRY.getJobDAO();
    this.anomalyTaskDAO = DAO_REGISTRY.getTaskDAO();
    this.monitorConfiguration = monitorConfiguration;
    scheduledExecutorService = Executors.newScheduledThreadPool(10);
  }

  public void start() {
    LOG.info("Starting monitor service");

    monitorJobContext = new MonitorJobContext();
    monitorJobContext.setJobDAO(anomalyJobDAO);
    monitorJobContext.setTaskDAO(anomalyTaskDAO);
    monitorJobContext.setMonitorConfiguration(monitorConfiguration);

    monitorJobRunner = new MonitorJobRunner(monitorJobContext);
    scheduledExecutorService
      .scheduleWithFixedDelay(monitorJobRunner, 0, monitorConfiguration.getMonitorFrequency().getSize(),
          monitorConfiguration.getMonitorFrequency().getUnit());
  }

  public void stop() {
    LOG.info("Stopping monitor service");
    scheduledExecutorService.shutdown();
  }
}
