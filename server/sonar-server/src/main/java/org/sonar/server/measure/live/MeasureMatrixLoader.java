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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ServerSide;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.measure.LiveMeasureDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.organization.OrganizationDto;

@ServerSide
public class MeasureMatrixLoader {

  private final DbClient dbClient;

  public MeasureMatrixLoader(DbClient dbClient) {
    this.dbClient = dbClient;
  }

  public MeasureMatrix load(DbSession dbSession, Collection<ComponentDto> componentsInSameProject, Collection<String> metricKeys) {
    List<MetricDto> metrics = dbClient.metricDao().selectByKeys(dbSession, metricKeys);
    Map<Integer, MetricDto> metricsPerId = metrics
      .stream()
      .collect(MoreCollectors.uniqueIndex(MetricDto::getId));

    Set<String> componentUuids = new HashSet<>();
    for (ComponentDto component : componentsInSameProject) {
      componentUuids.add(component.uuid());
      // ancestors, excluding self
      componentUuids.addAll(component.getUuidPathAsList());
    }
    List<ComponentDto> bottomUpComponents = loadAndOrderComponents(dbSession, componentUuids);
    ComponentDto project = bottomUpComponents.get(bottomUpComponents.size() - 1);
    OrganizationDto organization = loadOrganization(dbSession, project);

    List<LiveMeasureDto> dbMeasures = dbClient.liveMeasureDao().selectByComponentUuidsAndMetricIds(dbSession, componentUuids, metricsPerId.keySet());
    return new MeasureMatrix(organization, bottomUpComponents, metrics, dbMeasures);
  }

  private OrganizationDto loadOrganization(DbSession dbSession, ComponentDto project) {
    String organizationUuid = project.getOrganizationUuid();
    return dbClient.organizationDao().selectByUuid(dbSession, organizationUuid)
      .orElseThrow(() -> new IllegalStateException("No organization with UUID " + organizationUuid));
  }

  private List<ComponentDto> loadAndOrderComponents(DbSession dbSession, Set<String> componentUuids) {
    return Ordering
      .explicit(Qualifiers.ORDERED_BOTTOM_UP)
      .onResultOf(ComponentDto::qualifier)
      .sortedCopy(dbClient.componentDao().selectByUuids(dbSession, componentUuids));
  }
}
