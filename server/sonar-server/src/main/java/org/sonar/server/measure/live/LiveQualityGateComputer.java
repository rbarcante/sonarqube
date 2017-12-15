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

import java.util.Optional;
import java.util.OptionalDouble;
import org.sonar.api.measures.Metric;
import org.sonar.api.server.ServerSide;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.measure.LiveMeasureDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.server.qualitygate.QualityGateEvaluator;
import org.sonar.server.qualitygate.QualityGateFinder;

@ServerSide
public class LiveQualityGateComputer {

  private final DbClient dbClient;
  private final QualityGateFinder qGateFinder;
  private final QualityGateEvaluator evaluator;

  public LiveQualityGateComputer(DbClient dbClient, QualityGateFinder qGateFinder, QualityGateEvaluator evaluator) {
    this.dbClient = dbClient;
    this.qGateFinder = qGateFinder;
    this.evaluator = evaluator;
  }

  public void refreshGateStatus(DbSession dbSession, ComponentDto project) {
//    QualityGateDto gateDto = qGateFinder.getQualityGate(dbSession, project.getId()).get().getQualityGate();
//    Collection<QualityGateConditionDto> conditionDtos = dbClient.gateConditionDao().selectForQualityGate(dbSession, gateDto.getId());
//    Set<Integer> metricIds = conditionDtos.stream().map(c -> (int) c.getMetricId())
//      .collect(MoreCollectors.toHashSet(conditionDtos.size()));
//    List<MetricDto> metrics = dbClient.metricDao().selectByIds(dbSession, metricIds);
//    Map<Integer, MetricDto> metricsById = metrics.stream().collect(MoreCollectors.uniqueIndex(MetricDto::getId));
//
//    Set<Condition> conditions = conditionDtos.stream().map(conditionDto -> {
//      String metricKey = metricsById.get((int) conditionDto.getMetricId()).getKey();
//      Condition.Operator operator = Condition.Operator.fromDbValue(conditionDto.getOperator());
//      boolean onLeak = Objects.equals(conditionDto.getPeriod(), 1);
//      return new Condition(metricKey, operator, conditionDto.getErrorThreshold(), conditionDto.getWarningThreshold(), onLeak);
//    }).collect(MoreCollectors.toHashSet(conditionDtos.size()));
//
//    QualityGate gate = new QualityGate(String.valueOf(gateDto.getId()), gateDto.getName(), conditions);
//
//    Map<String, LiveMeasureDto> liveMeasuresByMetric = dbClient.liveMeasureDao()
//      .selectByComponentUuidsAndMetricIds(dbSession, Collections.singletonList(project.uuid()), metricIds)
//      .stream()
//      .collect(MoreCollectors.uniqueIndex(lm -> metricsById.get(lm.getMetricId()).getKey()));
//
//    QualityGateEvaluator.Measures measures = metricKey -> {
//      LiveMeasureDto liveMeasureDto = liveMeasuresByMetric.get(metricKey);
//      if (liveMeasureDto == null) {
//        return Optional.empty();
//      }
//      MetricDto metric = metricsById.get(liveMeasureDto.getMetricId());
//      return Optional.of(new LiveMeasure(liveMeasureDto, metric));
//    };
//
//
//    EvaluatedQualityGate evaluatedGate = evaluator.evaluate(gate, measures);

  }

  private static class LiveMeasure implements QualityGateEvaluator.Measure {
    private final LiveMeasureDto dto;
    private final MetricDto metric;

    LiveMeasure(LiveMeasureDto dto, MetricDto metric) {
      this.dto = dto;
      this.metric = metric;
    }

    @Override
    public Metric.ValueType getType() {
      return Metric.ValueType.valueOf(metric.getValueType());
    }

    @Override
    public OptionalDouble getValue() {
      if (dto.getValue() == null) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(dto.getValue());
    }

    @Override
    public Optional<String> getStringValue() {
      return Optional.ofNullable(dto.getTextValue());
    }

    @Override
    public OptionalDouble getLeakValue() {
      if (dto.getVariation() == null) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(dto.getVariation());
    }
  }
}
