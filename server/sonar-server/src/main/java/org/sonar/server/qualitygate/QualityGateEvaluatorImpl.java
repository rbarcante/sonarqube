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
package org.sonar.server.qualitygate;

import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.Set;
import java.util.function.Function;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.server.qualitygate.EvaluatedCondition.EvaluationStatus;

import static org.sonar.core.util.stream.MoreCollectors.toEnumSet;

public class QualityGateEvaluatorImpl implements QualityGateEvaluator {

  @Override
  public EvaluatedQualityGate evaluate(QualityGate gate, Measures measures) {
    EvaluatedQualityGate.Builder result = EvaluatedQualityGate.newBuilder()
      .setQualityGate(gate);

    Multimap<String, Condition> conditionsPerMetric = gate.getConditions().stream()
      .collect(MoreCollectors.index(Condition::getMetricKey, Function.identity()));
    conditionsPerMetric.asMap().values()
      .forEach(conditionsOnSameMetric -> result.addCondition(evaluateConditionsOnMetric(conditionsOnSameMetric, measures)));

    result.setStatus(overallStatusOf(result.getEvaluatedConditions()));

    return result.build();
  }

  private EvaluatedCondition evaluateConditionsOnMetric(Collection<Condition> conditionsOnSameMetric, Measures measures) {
    // TODO smallChangesetQualityGateSpecialCase
    EvaluatedCondition leakEvaluation = null;
    EvaluatedCondition absoluteEvaluation = null;
    for (Condition condition : conditionsOnSameMetric) {
      if (condition.isOnLeakPeriod()) {
        leakEvaluation = ConditionEvaluator.evaluate(condition, measures);
      } else {
        absoluteEvaluation = ConditionEvaluator.evaluate(condition, measures);
      }
    }

    if (leakEvaluation == null) {
      return absoluteEvaluation;
    }
    if (absoluteEvaluation == null) {
      return leakEvaluation;
    }
    // both conditions are present. Take the worse one. In case of equality, take
    // the one on the leak period
    if (absoluteEvaluation.getStatus().compareTo(leakEvaluation.getStatus()) > 0) {
      return absoluteEvaluation;
    }
    return leakEvaluation;
  }

  private static EvaluatedQualityGate.Status overallStatusOf(Set<EvaluatedCondition> conditions) {
    Set<EvaluationStatus> statuses = conditions.stream().map(EvaluatedCondition::getStatus).collect(toEnumSet(EvaluationStatus.class));
    if (statuses.contains(EvaluationStatus.ERROR)) {
      return EvaluatedQualityGate.Status.ERROR;
    }
    if (statuses.contains(EvaluationStatus.WARN)) {
      return EvaluatedQualityGate.Status.WARN;
    }
    return EvaluatedQualityGate.Status.OK;
  }

}
