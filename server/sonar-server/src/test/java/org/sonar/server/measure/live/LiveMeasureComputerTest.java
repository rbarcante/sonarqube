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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.db.DbSession;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.component.SnapshotTesting;
import org.sonar.db.organization.OrganizationTesting;
import org.sonar.server.qualitygate.EvaluatedQualityGate;
import org.sonar.server.qualitygate.changeevent.QGChangeEvent;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;


public class LiveMeasureComputerTest {

  @Test
  public void refresh_single_ComponentDto_returns_empty_if_no_results() {
    LiveMeasureComputer underTest = new FakeComputer(Collections.emptyList());

    Optional<QGChangeEvent> result = underTest.refresh(mock(DbSession.class), newProject());

    assertThat(result).isEmpty();
  }

  @Test
  public void refresh_single_ComponentDto_returns_single_result() {
    ComponentDto project = newProject();
    BranchDto branch = ComponentTesting.newBranchDto(project);
    SnapshotDto analysis = SnapshotTesting.newAnalysis(project);
    EvaluatedQualityGate evaluatedGate = mock(EvaluatedQualityGate.class);
    QGChangeEvent gateEvent = new QGChangeEvent(project, branch, analysis, new MapSettings().asConfig(), () -> Optional.of(evaluatedGate));
    LiveMeasureComputer underTest = new FakeComputer(singletonList(gateEvent));

    Optional<QGChangeEvent> result = underTest.refresh(mock(DbSession.class), project);

    assertThat(result).hasValue(gateEvent);
  }

  private static ComponentDto newProject() {
    return ComponentTesting.newPublicProjectDto(OrganizationTesting.newOrganizationDto());
  }

  private static class FakeComputer implements LiveMeasureComputer {
    private final List<QGChangeEvent> result;

    private FakeComputer(List<QGChangeEvent> result) {
      this.result = result;
    }

    @Override
    public List<QGChangeEvent> refresh(DbSession dbSession, Collection<ComponentDto> components) {
      return result;
    }
  }
}
