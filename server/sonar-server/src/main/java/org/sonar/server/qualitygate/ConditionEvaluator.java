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

import java.util.Optional;
import org.sonar.api.measures.Metric;
import org.sonar.server.qualitygate.EvaluatedCondition.EvaluationStatus;

import static java.util.Optional.of;

class ConditionEvaluator {

  private ConditionEvaluator() {
    // prevent instantiation
  }

  /**
   * Evaluates the condition for the specified measure
   */
  static EvaluatedCondition evaluate(Condition condition, QualityGateEvaluator.Measures measures) {
    Optional<QualityGateEvaluator.Measure> measure = measures.get(condition.getMetricKey());
    if (!measure.isPresent()) {
      return new EvaluatedCondition(condition, EvaluationStatus.OK, null);
    }

    Optional<Comparable> value = getMeasureValue(condition, measure.get());
    if (!value.isPresent()) {
      return new EvaluatedCondition(condition, EvaluationStatus.OK, null);
    }

    Metric.ValueType type = measure.get().getType();
    return evaluateCondition(condition, type, value.get(), true)
      .orElseGet(() -> evaluateCondition(condition, type, value.get(), false)
        .orElseGet(() -> new EvaluatedCondition(condition, EvaluationStatus.OK, value.get().toString())));
  }

  /**
   * Evaluates the error or warning condition. Returns empty if threshold or measure value is not defined.
   */
  private static Optional<EvaluatedCondition> evaluateCondition(Condition condition, Metric.ValueType type, Comparable value, boolean error) {
    Optional<Comparable> threshold = getThreshold(condition, type, error);
    if (!threshold.isPresent()) {
      return Optional.empty();
    }

    if (reachThreshold(value, threshold.get(), condition)) {
      EvaluationStatus status = error ? EvaluationStatus.ERROR : EvaluationStatus.WARN;
      return of(new EvaluatedCondition(condition, status, value.toString()));
    }
    return Optional.empty();
  }

  private static Optional<Comparable> getThreshold(Condition condition, Metric.ValueType valueType, boolean error) {
    Optional<String> valString = error ? condition.getErrorThreshold() : condition.getWarningThreshold();
    return valString.map(s -> {
      try {
        switch (valueType) {
          case BOOL:
            return parseInteger(s) == 1;
          case INT:
          case RATING:
            return parseInteger(s);
          case MILLISEC:
          case WORK_DUR:
            return Long.parseLong(s);
          case FLOAT:
          case PERCENT:
            return Double.parseDouble(s);
          case STRING:
          case LEVEL:
            return s;
          default:
            throw new IllegalArgumentException(String.format("Unsupported value type %s. Can not convert condition value", valueType));
        }
      } catch (NumberFormatException badValueFormat) {
        throw new IllegalArgumentException(String.format(
          "Quality Gate: Unable to parse threshold '%s' to compare against %s", s, condition.getMetricKey()));
      }
    });
  }

  private static Optional<Comparable> getMeasureValue(Condition condition, QualityGateEvaluator.Measure measure) {
    Comparable result = null;

    if (condition.isOnLeakPeriod()) {
      if (measure.getLeakValue().isPresent()) {
        switch (measure.getType()) {
          case BOOL:
            result = Double.compare(measure.getLeakValue().getAsDouble(), 1.0) == 1;
            break;
          case INT:
          case RATING:
            result = (int) measure.getLeakValue().getAsDouble();
            break;
          case FLOAT:
          case PERCENT:
            result = measure.getLeakValue().getAsDouble();
            break;
          case MILLISEC:
          case WORK_DUR:
            result = (long) measure.getLeakValue().getAsDouble();
            break;
          default:
            throw new IllegalArgumentException("Condition on leak period is not allowed for type " + measure.getType());
        }
      }
    } else {
      // not on leak
      switch (measure.getType()) {
        case BOOL:
          if (measure.getValue().isPresent()) {
            result = Double.compare(measure.getValue().getAsDouble(), 1.0) == 1;
          }
          break;
        case INT:
        case RATING:
          if (measure.getValue().isPresent()) {
            result = (int) measure.getValue().getAsDouble();
          }
          break;
        case FLOAT:
        case PERCENT:
          if (measure.getValue().isPresent()) {
            result = measure.getValue().getAsDouble();
          }
          break;
        case MILLISEC:
        case WORK_DUR:
          if (measure.getValue().isPresent()) {
            result = (long) measure.getValue().getAsDouble();
          }
          break;
        case LEVEL:
        case STRING:
        case DISTRIB:
          result = measure.getStringValue().orElse(null);
          break;
        default:
          throw new IllegalArgumentException("Condition on leak period is not allowed for type " + measure.getType());
      }
    }

    return Optional.ofNullable(result);
  }

  private static int parseInteger(String value) {
    return value.contains(".") ? Integer.parseInt(value.substring(0, value.indexOf('.'))) : Integer.parseInt(value);
  }

  private static boolean reachThreshold(Comparable measureValue, Comparable threshold, Condition condition) {
    int comparison = measureValue.compareTo(threshold);
    switch (condition.getOperator()) {
      case EQUALS:
        return comparison == 0;
      case NOT_EQUALS:
        return comparison != 0;
      case GREATER_THAN:
        return comparison > 0;
      case LESS_THAN:
        return comparison < 0;
      default:
        throw new IllegalArgumentException(String.format("Unsupported operator '%s'", condition.getOperator()));
    }
  }
}
