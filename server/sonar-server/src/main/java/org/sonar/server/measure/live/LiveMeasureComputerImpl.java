/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.measure.live;

import com.google.common.collect.Ordering;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.measure.LiveMeasureDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.server.computation.task.projectanalysis.qualitymodel.DebtRatingGrid;
import org.sonar.server.computation.task.projectanalysis.qualitymodel.Rating;
import org.sonar.server.qualitygate.EvaluatedQualityGate;
import org.sonar.server.qualitygate.QualityGate;
import org.sonar.server.qualitygate.changeevent.QGChangeEvent;
import org.sonar.server.settings.ProjectConfigurationLoader;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.groupingBy;
import static org.sonar.core.util.stream.MoreCollectors.toArrayList;
import static org.sonar.core.util.stream.MoreCollectors.uniqueIndex;

public class LiveMeasureComputerImpl implements LiveMeasureComputer {

  private final DbClient dbClient;
  private final IssueMetricFormulaFactory formulaFactory;
  private final LiveQualityGateComputer qGateComputer;
  private final ProjectConfigurationLoader projectConfigurationLoader;

  public LiveMeasureComputerImpl(DbClient dbClient, IssueMetricFormulaFactory formulaFactory,
    LiveQualityGateComputer qGateComputer, ProjectConfigurationLoader projectConfigurationLoader) {
    this.dbClient = dbClient;
    this.formulaFactory = formulaFactory;
    this.qGateComputer = qGateComputer;
    this.projectConfigurationLoader = projectConfigurationLoader;
  }

  @Override
  public List<QGChangeEvent> refresh(DbSession dbSession, Collection<ComponentDto> components) {
    if (components.isEmpty()) {
      return emptyList();
    }

    List<QGChangeEvent> result = new ArrayList<>();
    Map<String, List<ComponentDto>> componentsByProjectUuid = components.stream().collect(groupingBy(ComponentDto::projectUuid));
    for (List<ComponentDto> groupedComponents : componentsByProjectUuid.values()) {
      Optional<QGChangeEvent> qgChangeEvent = refreshComponentsOnSameProject(dbSession, groupedComponents);
      qgChangeEvent.ifPresent(result::add);
    }
    return result;
  }

  private Optional<QGChangeEvent> refreshComponentsOnSameProject(DbSession dbSession, List<ComponentDto> touchedComponents) {
    // load all the components to be refreshed, including their ancestors
    List<ComponentDto> components = loadTreeOfComponents(dbSession, touchedComponents);
    ComponentDto project = findProject(components);
    OrganizationDto organization = loadOrganization(dbSession, project);
    BranchDto branch = loadBranch(dbSession, project);

    Optional<SnapshotDto> lastAnalysis = dbClient.snapshotDao().selectLastAnalysisByRootComponentUuid(dbSession, project.uuid());
    if (!lastAnalysis.isPresent()) {
      return Optional.empty();
    }
    Optional<Long> beginningOfLeakPeriod = lastAnalysis.map(SnapshotDto::getPeriodDate);

    QualityGate qualityGate = qGateComputer.loadQualityGate(dbSession, organization, project, branch);
    Collection<String> metricKeys = getKeysOfAllInvolvedMetrics(qualityGate);

    List<MetricDto> metrics = dbClient.metricDao().selectByKeys(dbSession, metricKeys);
    Map<Integer, MetricDto> metricsPerId = metrics
      .stream()
      .collect(uniqueIndex(MetricDto::getId));
    List<String> componentUuids = components.stream().map(ComponentDto::uuid).collect(toArrayList(components.size()));
    List<LiveMeasureDto> dbMeasures = dbClient.liveMeasureDao().selectByComponentUuidsAndMetricIds(dbSession, componentUuids, metricsPerId.keySet());

    Configuration config = projectConfigurationLoader.loadProjectConfiguration(dbSession, project);
    DebtRatingGrid debtRatingGrid = new DebtRatingGrid(config);

    MeasureMatrix matrix = new MeasureMatrix(components, metrics, dbMeasures);
    components.forEach(c -> {
      IssueCounter issueCounter = new IssueCounter(dbClient.issueDao().selectIssueGroupsByBaseComponent(dbSession, c, beginningOfLeakPeriod.orElse(Long.MAX_VALUE)));
      FormulaContextImpl context = new FormulaContextImpl(matrix, debtRatingGrid);
      for (IssueMetricFormula formula : formulaFactory.getFormulas()) {
        // exclude leak formulas when leak period is not defined
        if (beginningOfLeakPeriod.isPresent() || !formula.isOnLeak()) {
          context.change(c, formula);
          try {
            formula.compute(context, issueCounter);
          } catch (RuntimeException e) {
            throw new IllegalStateException("Fail to compute " + formula.getMetric().getKey() + " on " + context.getComponent().getDbKey(), e);
          }
        }
      }
    });

    EvaluatedQualityGate evaluatedQualityGate = qGateComputer.refreshGateStatus(project, qualityGate, matrix);

    // persist the measures that have been created or updated
    matrix.getChanged().forEach(m -> dbClient.liveMeasureDao().insertOrUpdate(dbSession, m, null));
    dbSession.commit();

    return Optional.of(new QGChangeEvent(project, branch, lastAnalysis.get(), config, () -> Optional.of(evaluatedQualityGate)));
  }

  private List<ComponentDto> loadTreeOfComponents(DbSession dbSession, List<ComponentDto> touchedComponents) {
    Set<String> componentUuids = new HashSet<>();
    for (ComponentDto component : touchedComponents) {
      componentUuids.add(component.uuid());
      // ancestors, excluding self
      componentUuids.addAll(component.getUuidPathAsList());
    }
    return Ordering
      .explicit(Qualifiers.ORDERED_BOTTOM_UP)
      .onResultOf(ComponentDto::qualifier)
      .sortedCopy(dbClient.componentDao().selectByUuids(dbSession, componentUuids));
  }

  private Set<String> getKeysOfAllInvolvedMetrics(QualityGate gate) {
    Set<String> metricKeys = new HashSet<>();
    for (Metric metric : formulaFactory.getFormulaMetrics()) {
      metricKeys.add(metric.getKey());
    }
    metricKeys.addAll(qGateComputer.getMetricsRelatedTo(gate));
    return metricKeys;
  }

  private static ComponentDto findProject(Collection<ComponentDto> components) {
    return components.stream().filter(ComponentDto::isRootProject).findFirst()
      .orElseThrow(() -> new IllegalStateException("No project found in " + components));
  }

  private BranchDto loadBranch(DbSession dbSession, ComponentDto project) {
    return dbClient.branchDao().selectByUuid(dbSession, project.uuid())
      .orElseThrow(() -> new IllegalStateException("Branch not found: " + project.uuid()));
  }

  private OrganizationDto loadOrganization(DbSession dbSession, ComponentDto project) {
    String organizationUuid = project.getOrganizationUuid();
    return dbClient.organizationDao().selectByUuid(dbSession, organizationUuid)
      .orElseThrow(() -> new IllegalStateException("No organization with UUID " + organizationUuid));
  }

  private static class FormulaContextImpl implements IssueMetricFormula.Context {
    private final MeasureMatrix matrix;
    private final DebtRatingGrid debtRatingGrid;
    private ComponentDto currentComponent;
    private IssueMetricFormula currentFormula;

    private FormulaContextImpl(MeasureMatrix matrix, DebtRatingGrid debtRatingGrid) {
      this.matrix = matrix;
      this.debtRatingGrid = debtRatingGrid;
    }

    private void change(ComponentDto component, IssueMetricFormula formula) {
      this.currentComponent = component;
      this.currentFormula = formula;
    }

    @Override
    public ComponentDto getComponent() {
      return currentComponent;
    }

    @Override
    public DebtRatingGrid getDebtRatingGrid() {
      return debtRatingGrid;
    }

    @Override
    public OptionalDouble getValue(Metric metric) {
      return matrix.getValue(currentComponent, metric.getKey());
    }

    @Override
    public void setValue(double value) {
      String metricKey = currentFormula.getMetric().getKey();
      if (currentFormula.isOnLeak()) {
        matrix.setLeakValue(currentComponent, metricKey, value);
      } else {
        matrix.setValue(currentComponent, metricKey, value);
      }
    }

    @Override
    public void setValue(Rating value) {
      String metricKey = currentFormula.getMetric().getKey();
      if (currentFormula.isOnLeak()) {
        matrix.setLeakValue(currentComponent, metricKey, value);
      } else {
        matrix.setValue(currentComponent, metricKey, value);
      }
    }
  }
}
